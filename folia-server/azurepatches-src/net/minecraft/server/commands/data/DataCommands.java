package net.minecraft.server.commands.data;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.azurebranches.command.EntityLayer;
import com.azurebranches.command.ExpChainSupport;
import com.azurebranches.command.PhaseSnapshot;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.NbtTagArgument;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.PrimitiveTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.commands.RegionCommandExecutor;
import net.minecraft.util.Mth;

public class DataCommands {
    private static final SimpleCommandExceptionType ERROR_MERGE_UNCHANGED = new SimpleCommandExceptionType(Component.translatable("commands.data.merge.failed"));
    private static final DynamicCommandExceptionType ERROR_GET_NOT_NUMBER = new DynamicCommandExceptionType(
        path -> Component.translatableEscape("commands.data.get.invalid", path)
    );
    private static final DynamicCommandExceptionType ERROR_GET_NON_EXISTENT = new DynamicCommandExceptionType(
        path -> Component.translatableEscape("commands.data.get.unknown", path)
    );
    private static final SimpleCommandExceptionType ERROR_MULTIPLE_TAGS = new SimpleCommandExceptionType(Component.translatable("commands.data.get.multiple"));
    private static final DynamicCommandExceptionType ERROR_EXPECTED_OBJECT = new DynamicCommandExceptionType(
        node -> Component.translatableEscape("commands.data.modify.expected_object", node)
    );
    private static final DynamicCommandExceptionType ERROR_EXPECTED_VALUE = new DynamicCommandExceptionType(
        node -> Component.translatableEscape("commands.data.modify.expected_value", node)
    );
    private static final Dynamic2CommandExceptionType ERROR_INVALID_SUBSTRING = new Dynamic2CommandExceptionType(
        (start, end) -> Component.translatableEscape("commands.data.modify.invalid_substring", start, end)
    );
    public static final List<Function<String, DataCommands.DataProvider>> ALL_PROVIDERS = ImmutableList.of(
        EntityDataAccessor.PROVIDER, BlockDataAccessor.PROVIDER, StorageDataAccessor.PROVIDER
    );
    public static final List<DataCommands.DataProvider> TARGET_PROVIDERS = ALL_PROVIDERS.stream()
        .map(f -> f.apply("target"))
        .collect(ImmutableList.toImmutableList());
    public static final List<DataCommands.DataProvider> SOURCE_PROVIDERS = ALL_PROVIDERS.stream()
        .map(f -> f.apply("source"))
        .collect(ImmutableList.toImmutableList());

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("data").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

        for (DataCommands.DataProvider targetProvider : TARGET_PROVIDERS) {
            root.then(
                    targetProvider.wrap(
                        Commands.literal("merge"),
                        p -> p.then(
                            Commands.argument("nbt", CompoundTagArgument.compoundTag())
                                .executes(c -> mergeData(c.getSource(), targetProvider.access(c), CompoundTagArgument.getCompoundTag(c, "nbt")))
                        )
                    )
                )
                .then(
                    targetProvider.wrap(
                        Commands.literal("get"),
                        p -> p.executes(c -> getData(c.getSource(), targetProvider.access(c)))
                            .then(
                                Commands.argument("path", NbtPathArgument.nbtPath())
                                    .executes(c -> getData(c.getSource(), targetProvider.access(c), NbtPathArgument.getPath(c, "path")))
                                    .then(
                                        Commands.argument("scale", DoubleArgumentType.doubleArg())
                                            .executes(
                                                c -> getNumeric(
                                                    c.getSource(),
                                                    targetProvider.access(c),
                                                    NbtPathArgument.getPath(c, "path"),
                                                    DoubleArgumentType.getDouble(c, "scale")
                                                )
                                            )
                                    )
                            )
                    )
                )
                .then(
                    targetProvider.wrap(
                        Commands.literal("remove"),
                        p -> p.then(
                            Commands.argument("path", NbtPathArgument.nbtPath())
                                .executes(c -> removeData(c.getSource(), targetProvider.access(c), NbtPathArgument.getPath(c, "path")))
                        )
                    )
                )
                .then(
                    decorateModification(
                        (parent, rest) -> parent.then(
                                Commands.literal("insert")
                                    .then(
                                        Commands.argument("index", IntegerArgumentType.integer())
                                            .then(
                                                rest.create(
                                                    (context, target, targetPath, source) -> targetPath.insert(
                                                        IntegerArgumentType.getInteger(context, "index"), target, source
                                                    )
                                                )
                                            )
                                    )
                            )
                            .then(Commands.literal("prepend").then(rest.create((context, target, targetPath, source) -> targetPath.insert(0, target, source))))
                            .then(Commands.literal("append").then(rest.create((context, target, targetPath, source) -> targetPath.insert(-1, target, source))))
                            .then(
                                Commands.literal("set")
                                    .then(rest.create((context, target, targetPath, source) -> targetPath.set(target, Iterables.getLast(source))))
                            )
                            .then(Commands.literal("merge").then(rest.create((context, target, targetPath, source) -> {
                                CompoundTag combinedSources = new CompoundTag();

                                for (Tag sourceTag : source) {
                                    if (NbtPathArgument.NbtPath.isTooDeep(sourceTag, 0)) {
                                        throw NbtPathArgument.ERROR_DATA_TOO_DEEP.create();
                                    }

                                    if (!(sourceTag instanceof CompoundTag tag)) {
                                        throw ERROR_EXPECTED_OBJECT.create(sourceTag);
                                    }

                                    combinedSources.merge(tag);
                                }

                                Collection<Tag> targets = targetPath.getOrCreate(target, CompoundTag::new);
                                int changedCount = 0;

                                for (Tag targetTag : targets) {
                                    if (!(targetTag instanceof CompoundTag targetObject)) {
                                        throw ERROR_EXPECTED_OBJECT.create(targetTag);
                                    }

                                    CompoundTag originalTarget = targetObject.copy();
                                    targetObject.merge(combinedSources);
                                    changedCount += originalTarget.equals(targetObject) ? 0 : 1;
                                }

                                return changedCount;
                            })))
                    )
                );
        }

        dispatcher.register(root);
    }

    private static String getAsText(final Tag tag) throws CommandSyntaxException {
        return switch (tag) {
            case StringTag(String var7) -> var7;
            case PrimitiveTag primitiveTag -> primitiveTag.toString();
            default -> throw ERROR_EXPECTED_VALUE.create(tag);
        };
    }

    private static List<Tag> stringifyTagList(final List<Tag> source, final DataCommands.StringProcessor stringProcessor) throws CommandSyntaxException {
        List<Tag> result = new ArrayList<>(source.size());

        for (Tag tag : source) {
            String text = getAsText(tag);
            result.add(StringTag.valueOf(stringProcessor.process(text)));
        }

        return result;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> decorateModification(
        final BiConsumer<ArgumentBuilder<CommandSourceStack, ?>, DataCommands.DataManipulatorDecorator> nodeSupplier
    ) {
        LiteralArgumentBuilder<CommandSourceStack> modify = Commands.literal("modify");

        for (DataCommands.DataProvider targetProvider : TARGET_PROVIDERS) {
            targetProvider.wrap(
                modify,
                t -> {
                    ArgumentBuilder<CommandSourceStack, ?> targetPathNode = Commands.argument("targetPath", NbtPathArgument.nbtPath());

                    for (DataCommands.DataProvider sourceProvider : SOURCE_PROVIDERS) {
                        nodeSupplier.accept(
                            targetPathNode,
                            manipulator -> sourceProvider.wrap(
                                Commands.literal("from"),
                                s -> s.executes(c -> manipulateData(c, targetProvider, manipulator, getSingletonSource(c, sourceProvider)))
                                    .then(
                                        Commands.argument("sourcePath", NbtPathArgument.nbtPath())
                                            .executes(c -> manipulateData(c, targetProvider, manipulator, resolveSourcePath(c, sourceProvider)))
                                    )
                            )
                        );
                        nodeSupplier.accept(
                            targetPathNode,
                            manipulator -> sourceProvider.wrap(
                                Commands.literal("string"),
                                s -> s.executes(
                                        c -> manipulateData(c, targetProvider, manipulator, stringifyTagList(getSingletonSource(c, sourceProvider), str -> str))
                                    )
                                    .then(
                                        Commands.argument("sourcePath", NbtPathArgument.nbtPath())
                                            .executes(
                                                c -> manipulateData(
                                                    c, targetProvider, manipulator, stringifyTagList(resolveSourcePath(c, sourceProvider), str -> str)
                                                )
                                            )
                                            .then(
                                                Commands.argument("start", IntegerArgumentType.integer())
                                                    .executes(
                                                        c -> manipulateData(
                                                            c,
                                                            targetProvider,
                                                            manipulator,
                                                            stringifyTagList(
                                                                resolveSourcePath(c, sourceProvider),
                                                                str -> substring(str, IntegerArgumentType.getInteger(c, "start"))
                                                            )
                                                        )
                                                    )
                                                    .then(
                                                        Commands.argument("end", IntegerArgumentType.integer())
                                                            .executes(
                                                                c -> manipulateData(
                                                                    c,
                                                                    targetProvider,
                                                                    manipulator,
                                                                    stringifyTagList(
                                                                        resolveSourcePath(c, sourceProvider),
                                                                        str -> substring(
                                                                            str,
                                                                            IntegerArgumentType.getInteger(c, "start"),
                                                                            IntegerArgumentType.getInteger(c, "end")
                                                                        )
                                                                    )
                                                                )
                                                            )
                                                    )
                                            )
                                    )
                            )
                        );
                    }

                    nodeSupplier.accept(
                        targetPathNode, manipulator -> Commands.literal("value").then(Commands.argument("value", NbtTagArgument.nbtTag()).executes(c -> {
                            List<Tag> source = Collections.singletonList(NbtTagArgument.getNbtTag(c, "value"));
                            return manipulateData(c, targetProvider, manipulator, CompletableFuture.completedFuture(source));
                        }))
                    );
                    return t.then(targetPathNode);
                }
            );
        }

        return modify;
    }

    private static String validatedSubstring(final String input, final int start, final int end) throws CommandSyntaxException {
        if (start >= 0 && end <= input.length() && start <= end) {
            return input.substring(start, end);
        } else {
            throw ERROR_INVALID_SUBSTRING.create(start, end);
        }
    }

    private static String substring(final String input, final int start, final int end) throws CommandSyntaxException {
        int length = input.length();
        int absoluteStart = getOffset(start, length);
        int absoluteEnd = getOffset(end, length);
        return validatedSubstring(input, absoluteStart, absoluteEnd);
    }

    private static String substring(final String input, final int start) throws CommandSyntaxException {
        int length = input.length();
        return validatedSubstring(input, getOffset(start, length), length);
    }

    private static int getOffset(final int index, final int length) {
        return index >= 0 ? index : length + index;
    }

    // ----------------------------------------------------------------
    //  AzureBranches EXP5: asynchronous cross-region data access
    //  Every world read/write is a CompletableFuture; feedback is routed
    //  back to the source region via runOnSource. The region thread never
    //  blocks on a distant block/entity. Same-region access short-circuits
    //  synchronously inside the async primitives.
    // ----------------------------------------------------------------

    private static Component errorComponent(final Throwable ex) {
        Throwable cause = ex;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof CommandSyntaxException cse) {
            return ComponentUtils.fromMessage(cse.getRawMessage());
        }
        return Component.translatable("command.failed");
    }

    // ----------------------------------------------------------------
    //  AzureBranches EXP6: EntityLayer capture (command-layer injection)
    //  Every /data path read/write on an ENTITY is recorded into the active
    //  PhaseSnapshot (OCC read-set / write cache) so a concurrent external
    //  modification of the same entity NBT triggers Phase rollback + replay.
    //
    //  Threading: the snapshot is captured on the command's home thread and
    //  passed explicitly into async callbacks, so capture works both on the
    //  same-region fast path and the cross-region async path (the NBT maps
    //  of PhaseSnapshot are concurrent). Block/storage accessors are skipped.
    // ----------------------------------------------------------------

    /** Normalize a Tag into a plain owned value for snapshot storage. */
    private static Object leafValue(final Tag tag) {
        if (tag == null) {
            return null;
        }
        if (tag instanceof NumericTag numericTag) {
            return numericTag.box();
        }
        if (tag instanceof StringTag stringTag) {
            return stringTag.value();
        }
        return tag.copy(); // compound / list / array → owned deep copy
    }

    /** Resolve a path against an in-memory compound and normalize the first match. */
    private static Object readLeaf(final NbtPathArgument.NbtPath path, final CompoundTag data) throws CommandSyntaxException {
        final Collection<Tag> tags = path.get(data);
        if (tags.isEmpty()) {
            return null;
        }
        return leafValue(tags.iterator().next());
    }

    /** Record an entity-NBT path read into the PhaseSnapshot read-set. */
    private static void interceptEntityRead(
        final PhaseSnapshot snap,
        final DataAccessor accessor,
        final NbtPathArgument.NbtPath path,
        final Tag liveTag
    ) {
        if (snap == null || !(accessor instanceof EntityDataAccessor entityAccessor)) {
            return;
        }
        final String[] parts = EntityLayer.parsePathString(path.asString());
        EntityLayer.recordReadValue(snap, entityAccessor.entityId(), parts[0], parts[1], parts[2],
            leafValue(liveTag), 0L);
    }

    /** Record an entity-NBT path write into the PhaseSnapshot write cache. */
    private static void interceptEntityWrite(
        final PhaseSnapshot snap,
        final DataAccessor accessor,
        final NbtPathArgument.NbtPath path,
        final Object newVal,
        final Object oldVal
    ) {
        if (snap == null || !(accessor instanceof EntityDataAccessor entityAccessor)) {
            return;
        }
        if (Objects.equals(newVal, oldVal)) {
            return; // no net change → nothing to compensate or validate
        }
        final String[] parts = EntityLayer.parsePathString(path.asString());
        EntityLayer.interceptWrite(snap, entityAccessor.entityId(), parts[0], parts[1], parts[2],
            newVal, oldVal);
    }

    /**
     * Record a pathless {@code /data merge entity} write: top-level shallow
     * diff between the pre-merge and post-merge compounds.
     */
    private static void interceptEntityMergeWrite(
        final PhaseSnapshot snap,
        final DataAccessor accessor,
        final CompoundTag before,
        final CompoundTag after
    ) {
        if (snap == null || !(accessor instanceof EntityDataAccessor entityAccessor)) {
            return;
        }
        for (final String name : after.keySet()) {
            final Tag beforeTag = before.get(name);
            final Tag afterTag = after.get(name);
            if (!Objects.equals(beforeTag, afterTag)) {
                EntityLayer.interceptWrite(snap, entityAccessor.entityId(), name, null, null,
                    leafValue(afterTag), leafValue(beforeTag));
            }
        }
    }

    private static CompletableFuture<List<Tag>> getSingletonSource(final CommandContext<CommandSourceStack> context, final DataCommands.DataProvider sourceProvider) throws CommandSyntaxException {
        DataAccessor source = sourceProvider.access(context);
        return source.getDataAsync().thenApply(data -> Collections.<Tag>singletonList(data));
    }

    private static CompletableFuture<List<Tag>> resolveSourcePath(final CommandContext<CommandSourceStack> context, final DataCommands.DataProvider sourceProvider) throws CommandSyntaxException {
        final DataAccessor source = sourceProvider.access(context);
        final NbtPathArgument.NbtPath sourcePath = NbtPathArgument.getPath(context, "sourcePath");
        // EXP6: capture the snapshot on the home thread; the read itself is
        // recorded on whichever thread materializes the source compound.
        final PhaseSnapshot snap = ExpChainSupport.getPhaseSnapshot();
        return source.getDataAsync().thenApply(data -> {
            try {
                final List<Tag> tags = sourcePath.get(data);
                if (!tags.isEmpty()) {
                    interceptEntityRead(snap, source, sourcePath, tags.iterator().next());
                }
                return tags;
            } catch (CommandSyntaxException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static CompletableFuture<List<Tag>> stringifyTagList(final CompletableFuture<List<Tag>> source, final DataCommands.StringProcessor stringProcessor) {
        return source.thenApply(list -> {
            try {
                return stringifyTagList(list, stringProcessor);
            } catch (CommandSyntaxException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static int manipulateData(
        final CommandContext<CommandSourceStack> context,
        final DataCommands.DataProvider targetProvider,
        final DataCommands.DataManipulator manipulator,
        final CompletableFuture<List<Tag>> source
    ) throws CommandSyntaxException {
        final DataAccessor target = targetProvider.access(context);
        final NbtPathArgument.NbtPath targetPath = NbtPathArgument.getPath(context, "targetPath");
        final CommandSourceStack sourceStack = context.getSource();
        final CompletableFuture<CompoundTag> targetDataFuture = target.getDataAsync();
        // EXP6: capture the PhaseSnapshot on the home thread; interception
        // runs on this thread (fast path) or on the materializing thread
        // (async path) — the snapshot NBT maps are concurrent.
        final PhaseSnapshot snap = ExpChainSupport.getPhaseSnapshot();

        // EXP5 P0#3: same-region fast path — exact synchronous result when both
        // the source read and the target read complete synchronously.
        if (source.isDone() && targetDataFuture.isDone()) {
            try {
                final CompoundTag targetSnapshot = targetDataFuture.join();
                final Object oldVal = snap != null ? readLeaf(targetPath, targetSnapshot) : null;
                final int result = manipulator.modify(context, targetSnapshot, targetPath, source.join());
                if (result == 0) {
                    throw ERROR_MERGE_UNCHANGED.create();
                }
                final Object newVal = snap != null ? readLeaf(targetPath, targetSnapshot) : null;
                target.setData(targetSnapshot);
                // Record only after the write landed — a failed write must not
                // leave phantom compensation data in the snapshot.
                interceptEntityWrite(snap, target, targetPath, newVal, oldVal);
                sourceStack.sendSuccess(() -> target.getModifiedSuccess(), true);
                return result;
            } catch (CompletionException | CommandSyntaxException e) {
                sourceStack.sendFailure(errorComponent(e));
                return 0;
            }
        }

        source.thenCompose(src -> targetDataFuture.thenCompose(targetData -> {
            final int result;
            final Object oldVal;
            final Object newVal;
            try {
                oldVal = snap != null ? readLeaf(targetPath, targetData) : null;
                result = manipulator.modify(context, targetData, targetPath, src);
                if (result == 0) {
                    throw ERROR_MERGE_UNCHANGED.create();
                }
                newVal = snap != null ? readLeaf(targetPath, targetData) : null;
            } catch (CommandSyntaxException e) {
                return CompletableFuture.<Integer>failedFuture(e);
            }
            return target.setDataAsync(targetData).thenApply(v -> {
                interceptEntityWrite(snap, target, targetPath, newVal, oldVal);
                return result;
            });
        })).whenComplete((result, ex) -> RegionCommandExecutor.runOnSource(sourceStack, () -> {
            if (ex != null) {
                sourceStack.sendFailure(errorComponent(ex));
            } else {
                sourceStack.sendSuccess(() -> target.getModifiedSuccess(), true);
            }
        }));
        return 1;
    }

    private static int removeData(final CommandSourceStack source, final DataAccessor accessor, final NbtPathArgument.NbtPath path) {
        final CompletableFuture<CompoundTag> data = accessor.getDataAsync();
        // EXP6: capture the PhaseSnapshot on the home thread (see manipulateData).
        final PhaseSnapshot snap = ExpChainSupport.getPhaseSnapshot();

        // EXP5 P0#3: same-region fast path.
        if (data.isDone()) {
            try {
                final CompoundTag result = data.join();
                final Object oldVal = snap != null ? readLeaf(path, result) : null;
                final int count = path.remove(result);
                if (count == 0) {
                    throw ERROR_MERGE_UNCHANGED.create();
                }
                accessor.setData(result);
                interceptEntityWrite(snap, accessor, path, EntityLayer.REMOVED, oldVal);
                source.sendSuccess(() -> accessor.getModifiedSuccess(), true);
                return count;
            } catch (CompletionException | CommandSyntaxException e) {
                source.sendFailure(errorComponent(e));
                return 0;
            }
        }

        data.thenCompose(result -> {
            final int count;
            final Object oldVal;
            try {
                oldVal = snap != null ? readLeaf(path, result) : null;
                count = path.remove(result);
                if (count == 0) {
                    throw ERROR_MERGE_UNCHANGED.create();
                }
            } catch (CommandSyntaxException e) {
                return CompletableFuture.<Integer>failedFuture(e);
            }
            return accessor.setDataAsync(result).thenApply(v -> {
                interceptEntityWrite(snap, accessor, path, EntityLayer.REMOVED, oldVal);
                return count;
            });
        }).whenComplete((count, ex) -> RegionCommandExecutor.runOnSource(source, () -> {
            if (ex != null) {
                source.sendFailure(errorComponent(ex));
            } else {
                source.sendSuccess(() -> accessor.getModifiedSuccess(), true);
            }
        }));
        return 1;
    }

    public static Tag getSingleTag(final NbtPathArgument.NbtPath path, final DataAccessor accessor) throws CommandSyntaxException {
        return getSingleTag(path, accessor.getData());
    }

    private static Tag getSingleTag(final NbtPathArgument.NbtPath path, final CompoundTag data) throws CommandSyntaxException {
        Collection<Tag> tags = path.get(data);
        Iterator<Tag> iterator = tags.iterator();
        Tag result = iterator.next();
        if (iterator.hasNext()) {
            throw ERROR_MULTIPLE_TAGS.create();
        } else {
            return result;
        }
    }

    private static int getData(final CommandSourceStack source, final DataAccessor accessor, final NbtPathArgument.NbtPath path) {
        final CompletableFuture<CompoundTag> data = accessor.getDataAsync();
        // EXP6: capture the PhaseSnapshot on the home thread (see manipulateData).
        final PhaseSnapshot snap = ExpChainSupport.getPhaseSnapshot();

        // EXP5 P0#3: same-region fast path — exact value for /execute store etc.
        if (data.isDone()) {
            try {
                final Tag tag = getSingleTag(path, data.join());
                interceptEntityRead(snap, accessor, path, tag);
                final int result = switch (tag) {
                    case NumericTag numericTag -> Mth.floor(numericTag.doubleValue());
                    case CollectionTag collectionTag -> collectionTag.size();
                    case CompoundTag compoundTag -> compoundTag.size();
                    case StringTag(String s) -> s.length();
                    case EndTag ignored -> throw ERROR_GET_NON_EXISTENT.create(path.toString());
                    default -> throw new MatchException(null, null);
                };
                source.sendSuccess(() -> accessor.getPrintSuccess(tag), false);
                return result;
            } catch (CompletionException | CommandSyntaxException e) {
                source.sendFailure(errorComponent(e));
                return 0;
            }
        }

        data.whenComplete((d, ex) -> {
            Tag tag = null;
            CommandSyntaxException resolveError = null;
            if (ex == null) {
                try {
                    tag = getSingleTag(path, d);
                    interceptEntityRead(snap, accessor, path, tag);
                } catch (CommandSyntaxException e) {
                    resolveError = e;
                }
            }
            final Tag resolvedTag = tag;
            final CommandSyntaxException finalError = resolveError;
            RegionCommandExecutor.runOnSource(source, () -> {
                if (ex != null) {
                    source.sendFailure(errorComponent(ex));
                    return;
                }
                if (finalError != null) {
                    source.sendFailure(ComponentUtils.fromMessage(finalError.getRawMessage()));
                    return;
                }
                try {
                    int result = switch (resolvedTag) {
                        case NumericTag numericTag -> Mth.floor(numericTag.doubleValue());
                        case CollectionTag collectionTag -> collectionTag.size();
                        case CompoundTag compoundTag -> compoundTag.size();
                        case StringTag(String s) -> s.length();
                        case EndTag ignored -> throw ERROR_GET_NON_EXISTENT.create(path.toString());
                        default -> throw new MatchException(null, null);
                    };
                    source.sendSuccess(() -> accessor.getPrintSuccess(resolvedTag), false);
                } catch (CommandSyntaxException e) {
                    source.sendFailure(ComponentUtils.fromMessage(e.getRawMessage()));
                }
            });
        });
        return 1;
    }

    private static int getNumeric(final CommandSourceStack source, final DataAccessor accessor, final NbtPathArgument.NbtPath path, final double scale) {
        final CompletableFuture<CompoundTag> data = accessor.getDataAsync();
        // EXP6: capture the PhaseSnapshot on the home thread (see manipulateData).
        final PhaseSnapshot snap = ExpChainSupport.getPhaseSnapshot();

        // EXP5 P0#3: same-region fast path.
        if (data.isDone()) {
            try {
                final Tag tag = getSingleTag(path, data.join());
                interceptEntityRead(snap, accessor, path, tag);
                if (!(tag instanceof NumericTag)) {
                    throw ERROR_GET_NOT_NUMBER.create(path.toString());
                }
                final int result = Mth.floor(((NumericTag) tag).doubleValue() * scale);
                source.sendSuccess(() -> accessor.getPrintSuccess(path, scale, result), false);
                return result;
            } catch (CompletionException | CommandSyntaxException e) {
                source.sendFailure(errorComponent(e));
                return 0;
            }
        }

        data.whenComplete((d, ex) -> {
            Tag tag = null;
            CommandSyntaxException resolveError = null;
            if (ex == null) {
                try {
                    tag = getSingleTag(path, d);
                    interceptEntityRead(snap, accessor, path, tag);
                } catch (CommandSyntaxException e) {
                    resolveError = e;
                }
            }
            final Tag resolvedTag = tag;
            final CommandSyntaxException finalError = resolveError;
            RegionCommandExecutor.runOnSource(source, () -> {
                if (ex != null) {
                    source.sendFailure(errorComponent(ex));
                    return;
                }
                if (finalError != null) {
                    source.sendFailure(ComponentUtils.fromMessage(finalError.getRawMessage()));
                    return;
                }
                if (!(resolvedTag instanceof NumericTag)) {
                    source.sendFailure(ComponentUtils.fromMessage(ERROR_GET_NOT_NUMBER.create(path.toString()).getRawMessage()));
                    return;
                }
                int result = Mth.floor(((NumericTag) resolvedTag).doubleValue() * scale);
                source.sendSuccess(() -> accessor.getPrintSuccess(path, scale, result), false);
            });
        });
        return 1;
    }

    private static int getData(final CommandSourceStack source, final DataAccessor accessor) {
        final CompletableFuture<CompoundTag> data = accessor.getDataAsync();

        // EXP5 P0#3: same-region fast path.
        if (data.isDone()) {
            try {
                final CompoundTag d = data.join();
                source.sendSuccess(() -> accessor.getPrintSuccess(d), false);
                return 1;
            } catch (CompletionException e) {
                source.sendFailure(errorComponent(e));
                return 0;
            }
        }

        data.whenComplete((d, ex) -> RegionCommandExecutor.runOnSource(source, () -> {
            if (ex != null) {
                source.sendFailure(errorComponent(ex));
            } else {
                source.sendSuccess(() -> accessor.getPrintSuccess(d), false);
            }
        }));
        return 1;
    }

    private static int mergeData(final CommandSourceStack source, final DataAccessor accessor, final CompoundTag nbt) {
        final CompletableFuture<CompoundTag> data = accessor.getDataAsync();
        // EXP6: capture the PhaseSnapshot on the home thread (see manipulateData).
        final PhaseSnapshot snap = ExpChainSupport.getPhaseSnapshot();

        // EXP5 P0#3: same-region fast path.
        if (data.isDone()) {
            try {
                final CompoundTag old = data.join();
                if (NbtPathArgument.NbtPath.isTooDeep(nbt, 0)) {
                    throw NbtPathArgument.ERROR_DATA_TOO_DEEP.create();
                }
                final CompoundTag result = old.copy().merge(nbt);
                if (old.equals(result)) {
                    throw ERROR_MERGE_UNCHANGED.create();
                }
                accessor.setData(result);
                interceptEntityMergeWrite(snap, accessor, old, result);
                source.sendSuccess(() -> accessor.getModifiedSuccess(), true);
                return 1;
            } catch (CompletionException | CommandSyntaxException e) {
                source.sendFailure(errorComponent(e));
                return 0;
            }
        }

        data.thenCompose(old -> {
            try {
                if (NbtPathArgument.NbtPath.isTooDeep(nbt, 0)) {
                    throw NbtPathArgument.ERROR_DATA_TOO_DEEP.create();
                }
                CompoundTag result = old.copy().merge(nbt);
                if (old.equals(result)) {
                    throw ERROR_MERGE_UNCHANGED.create();
                }
                return accessor.setDataAsync(result).thenApply(v -> {
                    interceptEntityMergeWrite(snap, accessor, old, result);
                    return null;
                });
            } catch (CommandSyntaxException e) {
                return CompletableFuture.<Void>failedFuture(e);
            }
        }).whenComplete((v, ex) -> RegionCommandExecutor.runOnSource(source, () -> {
            if (ex != null) {
                source.sendFailure(errorComponent(ex));
            } else {
                source.sendSuccess(() -> accessor.getModifiedSuccess(), true);
            }
        }));
        return 1;
    }

    @FunctionalInterface
    private interface DataManipulator {
        int modify(CommandContext<CommandSourceStack> context, CompoundTag targetData, NbtPathArgument.NbtPath targetPath, List<Tag> source) throws CommandSyntaxException;
    }

    @FunctionalInterface
    private interface DataManipulatorDecorator {
        ArgumentBuilder<CommandSourceStack, ?> create(DataCommands.DataManipulator manipulator);
    }

    public interface DataProvider {
        DataAccessor access(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;

        ArgumentBuilder<CommandSourceStack, ?> wrap(
            ArgumentBuilder<CommandSourceStack, ?> parent, Function<ArgumentBuilder<CommandSourceStack, ?>, ArgumentBuilder<CommandSourceStack, ?>> function
        );
    }

    @FunctionalInterface
    private interface StringProcessor {
        String process(String string) throws CommandSyntaxException;
    }
}
