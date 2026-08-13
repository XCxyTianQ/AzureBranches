package net.minecraft.server.commands;

import com.google.common.collect.Sets;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.world.entity.Entity;

/**
 * AzureBranches EXP4Plus: /tag restored with a fully asynchronous, non-blocking
 * execution model. Every per-entity read/modify hops to the owning region via
 * {@code RegionCommandExecutor.onEntityAsync} and the success/failure feedback is
 * routed back to the source region via {@code runOnSource}; the region thread
 * never blocks on a distant entity.
 */
public class TagCommand {
    private static final SimpleCommandExceptionType ERROR_ADD_FAILED = new SimpleCommandExceptionType(Component.translatable("commands.tag.add.failed"));
    private static final SimpleCommandExceptionType ERROR_REMOVE_FAILED = new SimpleCommandExceptionType(Component.translatable("commands.tag.remove.failed"));

    private record TagResult(boolean changed, Component displayName, Set<String> tags) {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("tag")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.argument("targets", EntityArgument.entities())
                        .then(
                            Commands.literal("add")
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .executes(c -> addTag(c.getSource(), EntityArgument.getEntities(c, "targets"), StringArgumentType.getString(c, "name")))
                                )
                        )
                        .then(
                            Commands.literal("remove")
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, p) -> suggestTags(EntityArgument.getEntities(c, "targets"), p))
                                        .executes(
                                            c -> removeTag(c.getSource(), EntityArgument.getEntities(c, "targets"), StringArgumentType.getString(c, "name"))
                                        )
                                )
                        )
                        .then(Commands.literal("list").executes(c -> listTags(c.getSource(), EntityArgument.getEntities(c, "targets"))))
                )
        );
    }

    private static CompletableFuture<Suggestions> suggestTags(final Collection<? extends Entity> entities, final com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return gatherTagsAsync(entities).thenCompose(tags -> SharedSuggestionProvider.suggest(tags, builder));
    }

    private static CompletableFuture<Collection<String>> gatherTagsAsync(final Collection<? extends Entity> entities) {
        final List<CompletableFuture<Set<String>>> futures = new ArrayList<>(entities.size());
        for (final Entity entity : entities) {
            futures.add(RegionCommandExecutor.onEntityAsync(entity, (Entity e) -> Sets.newHashSet(e.entityTags())));
        }
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(v -> {
            final Set<String> result = Sets.newHashSet();
            for (final CompletableFuture<Set<String>> f : futures) {
                result.addAll(f.join());
            }
            return result;
        });
    }

    private static List<CompletableFuture<TagResult>> collect(final Collection<? extends Entity> targets, final java.util.function.Function<Entity, TagResult> op) {
        final List<CompletableFuture<TagResult>> futures = new ArrayList<>(targets.size());
        for (final Entity entity : targets) {
            futures.add(RegionCommandExecutor.onEntityAsync(entity, (Entity e) -> op.apply(e)));
        }
        return futures;
    }

    private static int addTag(final CommandSourceStack source, final Collection<? extends Entity> targets, final String name) {
        final List<CompletableFuture<TagResult>> futures = collect(targets, e -> new TagResult(e.addTag(name), e.getDisplayName(), null));
        joinAndReport(source, futures, () -> ERROR_ADD_FAILED.create(), (count, names) -> {
            if (targets.size() == 1) {
                source.sendSuccess(() -> Component.translatable("commands.tag.add.success.single", name, names.get(0)), true);
            } else {
                source.sendSuccess(() -> Component.translatable("commands.tag.add.success.multiple", name, count), true);
            }
        });
        return targets.size(); // optimistic placeholder; the real result completes asynchronously
    }

    private static int removeTag(final CommandSourceStack source, final Collection<? extends Entity> targets, final String name) {
        final List<CompletableFuture<TagResult>> futures = collect(targets, e -> new TagResult(e.removeTag(name), e.getDisplayName(), null));
        joinAndReport(source, futures, () -> ERROR_REMOVE_FAILED.create(), (count, names) -> {
            if (targets.size() == 1) {
                source.sendSuccess(() -> Component.translatable("commands.tag.remove.success.single", name, names.get(0)), true);
            } else {
                source.sendSuccess(() -> Component.translatable("commands.tag.remove.success.multiple", name, count), true);
            }
        });
        return targets.size();
    }

    private static int listTags(final CommandSourceStack source, final Collection<? extends Entity> targets) {
        final List<CompletableFuture<TagResult>> futures = collect(targets, e -> new TagResult(false, e.getDisplayName(), Sets.newHashSet(e.entityTags())));
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, ex) ->
            RegionCommandExecutor.runOnSource(source, () -> {
                if (ex != null) {
                    source.sendFailure(Component.literal("commands.tag.failed"));
                    return;
                }
                final Set<String> tags = Sets.newHashSet();
                final List<Component> names = new ArrayList<>(futures.size());
                for (final CompletableFuture<TagResult> f : futures) {
                    final TagResult r = f.join();
                    names.add(r.displayName());
                    tags.addAll(r.tags());
                }
                if (targets.size() == 1) {
                    if (tags.isEmpty()) {
                        source.sendSuccess(() -> Component.translatable("commands.tag.list.single.empty", names.get(0)), false);
                    } else {
                        source.sendSuccess(() -> Component.translatable("commands.tag.list.single.success", names.get(0), tags.size(), ComponentUtils.formatList(tags)), false);
                    }
                } else if (tags.isEmpty()) {
                    source.sendSuccess(() -> Component.translatable("commands.tag.list.multiple.empty", targets.size()), false);
                } else {
                    source.sendSuccess(() -> Component.translatable("commands.tag.list.multiple.success", targets.size(), tags.size(), ComponentUtils.formatList(tags)), false);
                }
            })
        );
        return targets.size();
    }

    private static void joinAndReport(
        final CommandSourceStack source,
        final List<CompletableFuture<TagResult>> futures,
        final java.util.function.Supplier<CommandSyntaxException> errorSupplier,
        final java.util.function.BiConsumer<Integer, List<Component>> success
    ) {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, ex) ->
            RegionCommandExecutor.runOnSource(source, () -> {
                if (ex != null) {
                    source.sendFailure(Component.literal("commands.tag.failed"));
                    return;
                }
                int count = 0;
                final List<Component> names = new ArrayList<>(futures.size());
                for (final CompletableFuture<TagResult> f : futures) {
                    final TagResult r = f.join();
                    if (r.changed()) {
                        count++;
                        names.add(r.displayName());
                    }
                }
                if (count == 0) {
                    final CommandSyntaxException err = errorSupplier.get();
                    if (err != null) {
                        source.sendFailure(ComponentUtils.fromMessage(err.getRawMessage()));
                    }
                } else {
                    success.accept(count, names);
                }
            })
        );
    }
}
