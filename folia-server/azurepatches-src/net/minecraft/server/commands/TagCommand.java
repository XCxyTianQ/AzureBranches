package net.minecraft.server.commands;

import com.google.common.collect.Sets;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.world.entity.Entity;

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
                                        .suggests((c, p) -> SharedSuggestionProvider.suggest(getTags(EntityArgument.getEntities(c, "targets")), p))
                                        .executes(
                                            c -> removeTag(c.getSource(), EntityArgument.getEntities(c, "targets"), StringArgumentType.getString(c, "name"))
                                        )
                                )
                        )
                        .then(Commands.literal("list").executes(c -> listTags(c.getSource(), EntityArgument.getEntities(c, "targets"))))
                )
        );
    }

    private static Collection<String> getTags(final Collection<? extends Entity> entities) throws CommandSyntaxException {
        Set<String> result = Sets.newHashSet();
        for (Entity entity : entities) {
            // AzureBranches: read tags on the region owning the entity
            result.addAll(
                RegionCommandExecutor.onEntity(entity, (Entity e) -> Sets.newHashSet(e.entityTags()))
            );
        }
        return result;
    }

    private static int addTag(final CommandSourceStack source, final Collection<? extends Entity> targets, final String name) throws CommandSyntaxException {
        int count = 0;
        List<Component> names = new ArrayList<>();

        for (Entity entity : targets) {
            // AzureBranches: modify tags on the region owning the entity
            TagResult result = RegionCommandExecutor.onEntity(entity, (Entity e) ->
                new TagResult(e.addTag(name), e.getDisplayName(), null));
            if (result.changed()) {
                count++;
                names.add(result.displayName());
            }
        }

        if (count == 0) {
            throw ERROR_ADD_FAILED.create();
        } else {
            if (targets.size() == 1) {
                source.sendSuccess(() -> Component.translatable("commands.tag.add.success.single", name, names.get(0)), true);
            } else {
                final int finalCount = count;
                source.sendSuccess(() -> Component.translatable("commands.tag.add.success.multiple", name, finalCount), true);
            }

            return count;
        }
    }

    private static int removeTag(final CommandSourceStack source, final Collection<? extends Entity> targets, final String name) throws CommandSyntaxException {
        int count = 0;
        List<Component> names = new ArrayList<>();

        for (Entity entity : targets) {
            // AzureBranches: modify tags on the region owning the entity
            TagResult result = RegionCommandExecutor.onEntity(entity, (Entity e) ->
                new TagResult(e.removeTag(name), e.getDisplayName(), null));
            if (result.changed()) {
                count++;
                names.add(result.displayName());
            }
        }

        if (count == 0) {
            throw ERROR_REMOVE_FAILED.create();
        } else {
            if (targets.size() == 1) {
                source.sendSuccess(() -> Component.translatable("commands.tag.remove.success.single", name, names.get(0)), true);
            } else {
                final int finalCount = count;
                source.sendSuccess(() -> Component.translatable("commands.tag.remove.success.multiple", name, finalCount), true);
            }

            return count;
        }
    }

    private static int listTags(final CommandSourceStack source, final Collection<? extends Entity> targets) throws CommandSyntaxException {
        Set<String> tags = Sets.newHashSet();
        List<Component> names = new ArrayList<>();

        for (Entity entity : targets) {
            // AzureBranches: read tags on the region owning the entity
            TagResult result = RegionCommandExecutor.onEntity(entity, (Entity e) ->
                new TagResult(false, e.getDisplayName(), Sets.newHashSet(e.entityTags())));
            tags.addAll(result.tags());
            names.add(result.displayName());
        }

        if (targets.size() == 1) {
            if (tags.isEmpty()) {
                source.sendSuccess(() -> Component.translatable("commands.tag.list.single.empty", names.get(0)), false);
            } else {
                source.sendSuccess(
                    () -> Component.translatable("commands.tag.list.single.success", names.get(0), tags.size(), ComponentUtils.formatList(tags)),
                    false
                );
            }
        } else if (tags.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.tag.list.multiple.empty", targets.size()), false);
        } else {
            source.sendSuccess(
                () -> Component.translatable("commands.tag.list.multiple.success", targets.size(), tags.size(), ComponentUtils.formatList(tags)), false
            );
        }

        return tags.size();
    }
}
