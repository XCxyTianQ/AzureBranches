package net.minecraft.server.commands;

import com.google.common.collect.Lists;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.commands.arguments.ObjectiveArgument;
import net.minecraft.commands.arguments.ObjectiveCriteriaArgument;
import net.minecraft.commands.arguments.OperationArgument;
import net.minecraft.commands.arguments.ScoreHolderArgument;
import net.minecraft.commands.arguments.ScoreboardSlotArgument;
import net.minecraft.commands.arguments.StyleArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jspecify.annotations.Nullable;

/**
 * AzureBranches EXP5Plus: /scoreboard restored for Folia region threading.
 *
 * <p>The scoreboard is server-global data (see {@code ServerScoreboard} — every
 * change broadcasts packets to all players). It is only safe to touch on the
 * Folia global tick thread, so every terminal handler is dispatched through
 * {@link #dispatch}, which queues the whole handler — including argument
 * resolution ({@code ObjectiveArgument}, {@code ScoreHolderArgument} selectors)
 * — onto the global tick thread via
 * {@link RegionCommandExecutor#onGlobalAsync}.</p>
 *
 * <p>Feedback is routed back to the source's region through
 * {@link RegionCommandExecutor#runOnSource} (console sources have a null level
 * and run inline). Entity-backed score holders ({@code @e} selectors, player
 * names) have their display names captured on the entity's own region thread
 * via {@link #withFeedbackName}, mirroring the TagCommand pattern. Suggestion
 * providers read the scoreboard on the suggestion thread — read-only and
 * accepted best-effort, same as the restored /trigger command.</p>
 */
public class ScoreboardCommand {
    private static final SimpleCommandExceptionType ERROR_OBJECTIVE_ALREADY_EXISTS = new SimpleCommandExceptionType(
        Component.translatable("commands.scoreboard.objectives.add.duplicate")
    );
    private static final SimpleCommandExceptionType ERROR_DISPLAY_SLOT_ALREADY_EMPTY = new SimpleCommandExceptionType(
        Component.translatable("commands.scoreboard.objectives.display.alreadyEmpty")
    );
    private static final SimpleCommandExceptionType ERROR_DISPLAY_SLOT_ALREADY_SET = new SimpleCommandExceptionType(
        Component.translatable("commands.scoreboard.objectives.display.alreadySet")
    );
    private static final SimpleCommandExceptionType ERROR_TRIGGER_ALREADY_ENABLED = new SimpleCommandExceptionType(
        Component.translatable("commands.scoreboard.players.enable.failed")
    );
    private static final SimpleCommandExceptionType ERROR_NOT_TRIGGER = new SimpleCommandExceptionType(
        Component.translatable("commands.scoreboard.players.enable.invalid")
    );
    private static final Dynamic2CommandExceptionType ERROR_NO_VALUE = new Dynamic2CommandExceptionType(
        (objective, target) -> Component.translatableEscape("commands.scoreboard.players.get.null", objective, target)
    );

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext context) {
        dispatcher.register(
            Commands.literal("scoreboard")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("objectives")
                        .then(
                            Commands.literal("list")
                                .executes(c -> dispatch(c.getSource(), () -> listObjectives(c.getSource())))
                        )
                        .then(
                            Commands.literal("add")
                                .then(
                                    Commands.argument("objective", StringArgumentType.word())
                                        .then(
                                            Commands.argument("criteria", ObjectiveCriteriaArgument.criteria())
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> addObjective(
                                                            c.getSource(),
                                                            StringArgumentType.getString(c, "objective"),
                                                            ObjectiveCriteriaArgument.getCriteria(c, "criteria"),
                                                            Component.literal(StringArgumentType.getString(c, "objective"))
                                                        )
                                                    )
                                                )
                                                .then(
                                                    Commands.argument("displayName", ComponentArgument.textComponent(context))
                                                        .executes(
                                                            c -> dispatch(
                                                                c.getSource(),
                                                                () -> addObjective(
                                                                    c.getSource(),
                                                                    StringArgumentType.getString(c, "objective"),
                                                                    ObjectiveCriteriaArgument.getCriteria(c, "criteria"),
                                                                    ComponentArgument.getResolvedComponent(c, "displayName")
                                                                )
                                                            )
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("modify")
                                .then(
                                    Commands.argument("objective", ObjectiveArgument.objective())
                                        .then(
                                            Commands.literal("displayname")
                                                .then(
                                                    Commands.argument("displayName", ComponentArgument.textComponent(context))
                                                        .executes(
                                                            c -> dispatch(
                                                                c.getSource(),
                                                                () -> setDisplayName(
                                                                    c.getSource(),
                                                                    ObjectiveArgument.getObjective(c, "objective"),
                                                                    ComponentArgument.getResolvedComponent(c, "displayName")
                                                                )
                                                            )
                                                        )
                                                )
                                        )
                                        .then(createRenderTypeModify())
                                        .then(
                                            Commands.literal("displayautoupdate")
                                                .then(
                                                    Commands.argument("value", BoolArgumentType.bool())
                                                        .executes(
                                                            c -> dispatch(
                                                                c.getSource(),
                                                                () -> setDisplayAutoUpdate(
                                                                    c.getSource(),
                                                                    ObjectiveArgument.getObjective(c, "objective"),
                                                                    BoolArgumentType.getBool(c, "value")
                                                                )
                                                            )
                                                        )
                                                )
                                        )
                                        .then(
                                            addNumberFormats(
                                                context,
                                                Commands.literal("numberformat"),
                                                (c, numberFormat) -> dispatch(
                                                    c.getSource(),
                                                    () -> setObjectiveFormat(
                                                        c.getSource(), ObjectiveArgument.getObjective(c, "objective"), numberFormat
                                                    )
                                                )
                                            )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("remove")
                                .then(
                                    Commands.argument("objective", ObjectiveArgument.objective())
                                        .executes(
                                            c -> dispatch(
                                                c.getSource(),
                                                () -> removeObjective(c.getSource(), ObjectiveArgument.getObjective(c, "objective"))
                                            )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("setdisplay")
                                .then(
                                    Commands.argument("slot", ScoreboardSlotArgument.displaySlot())
                                        .executes(
                                            c -> dispatch(
                                                c.getSource(),
                                                () -> clearDisplaySlot(c.getSource(), ScoreboardSlotArgument.getDisplaySlot(c, "slot"))
                                            )
                                        )
                                        .then(
                                            Commands.argument("objective", ObjectiveArgument.objective())
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> setDisplaySlot(
                                                            c.getSource(),
                                                            ScoreboardSlotArgument.getDisplaySlot(c, "slot"),
                                                            ObjectiveArgument.getObjective(c, "objective")
                                                        )
                                                    )
                                                )
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("players")
                        .then(
                            Commands.literal("list")
                                .executes(c -> dispatch(c.getSource(), () -> listTrackedPlayers(c.getSource())))
                                .then(
                                    Commands.argument("target", ScoreHolderArgument.scoreHolder())
                                        .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                        .executes(
                                            c -> {
                                                // AzureBranches EXP6Plus: resolve the holder on the
                                                // source thread — entity iteration is region data and
                                                // is not safe on the global tick thread.
                                                final ScoreHolder target = ScoreHolderArgument.getName(c, "target");
                                                return dispatch(
                                                    c.getSource(),
                                                    () -> listTrackedPlayerScores(c.getSource(), target)
                                                );
                                            }
                                        )
                                )
                        )
                        .then(
                            Commands.literal("set")
                                .then(
                                    Commands.argument("targets", ScoreHolderArgument.scoreHolders())
                                        .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                        .then(
                                            Commands.argument("objective", ObjectiveArgument.objective())
                                                .then(
                                                    Commands.argument("score", IntegerArgumentType.integer())
                                                        .executes(
                                                            c -> {
                                                                final Collection<ScoreHolder> targets =
                                                                    ScoreHolderArgument.getNamesWithDefaultWildcard(c, "targets");
                                                                return dispatch(
                                                                    c.getSource(),
                                                                    () -> setScore(
                                                                        c.getSource(),
                                                                        targets,
                                                                        ObjectiveArgument.getWritableObjective(c, "objective"),
                                                                        IntegerArgumentType.getInteger(c, "score")
                                                                    )
                                                                );
                                                            }
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("get")
                                .then(
                                    Commands.argument("target", ScoreHolderArgument.scoreHolder())
                                        .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                        .then(
                                            Commands.argument("objective", ObjectiveArgument.objective())
                                                .executes(
                                                    c -> {
                                                        final ScoreHolder target = ScoreHolderArgument.getName(c, "target");
                                                        return dispatch(
                                                            c.getSource(),
                                                            () -> getScore(
                                                                c.getSource(), target, ObjectiveArgument.getObjective(c, "objective")
                                                            )
                                                        );
                                                    }
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("add")
                                .then(
                                    Commands.argument("targets", ScoreHolderArgument.scoreHolders())
                                        .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                        .then(
                                            Commands.argument("objective", ObjectiveArgument.objective())
                                                .then(
                                                    Commands.argument("score", IntegerArgumentType.integer(0))
                                                        .executes(
                                                            c -> {
                                                                final Collection<ScoreHolder> targets =
                                                                    ScoreHolderArgument.getNamesWithDefaultWildcard(c, "targets");
                                                                return dispatch(
                                                                    c.getSource(),
                                                                    () -> addScore(
                                                                        c.getSource(),
                                                                        targets,
                                                                        ObjectiveArgument.getWritableObjective(c, "objective"),
                                                                        IntegerArgumentType.getInteger(c, "score")
                                                                    )
                                                                );
                                                            }
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("remove")
                                .then(
                                    Commands.argument("targets", ScoreHolderArgument.scoreHolders())
                                        .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                        .then(
                                            Commands.argument("objective", ObjectiveArgument.objective())
                                                .then(
                                                    Commands.argument("score", IntegerArgumentType.integer(0))
                                                        .executes(
                                                            c -> {
                                                                final Collection<ScoreHolder> targets =
                                                                    ScoreHolderArgument.getNamesWithDefaultWildcard(c, "targets");
                                                                return dispatch(
                                                                    c.getSource(),
                                                                    () -> removeScore(
                                                                        c.getSource(),
                                                                        targets,
                                                                        ObjectiveArgument.getWritableObjective(c, "objective"),
                                                                        IntegerArgumentType.getInteger(c, "score")
                                                                    )
                                                                );
                                                            }
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("reset")
                                .then(
                                    Commands.argument("targets", ScoreHolderArgument.scoreHolders())
                                        .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                        .executes(
                                            c -> {
                                                final Collection<ScoreHolder> targets =
                                                    ScoreHolderArgument.getNamesWithDefaultWildcard(c, "targets");
                                                return dispatch(
                                                    c.getSource(),
                                                    () -> resetScores(c.getSource(), targets)
                                                );
                                            }
                                        )
                                        .then(
                                            Commands.argument("objective", ObjectiveArgument.objective())
                                                .executes(
                                                    c -> {
                                                        final Collection<ScoreHolder> targets =
                                                            ScoreHolderArgument.getNamesWithDefaultWildcard(c, "targets");
                                                        return dispatch(
                                                            c.getSource(),
                                                            () -> resetScore(
                                                                c.getSource(),
                                                                targets,
                                                                ObjectiveArgument.getObjective(c, "objective")
                                                            )
                                                        );
                                                    }
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("enable")
                                .then(
                                    Commands.argument("targets", ScoreHolderArgument.scoreHolders())
                                        .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                        .then(
                                            Commands.argument("objective", ObjectiveArgument.objective())
                                                .suggests(
                                                    (c, p) -> suggestTriggers(c.getSource(), ScoreHolderArgument.getNamesWithDefaultWildcard(c, "targets"), p)
                                                )
                                                .executes(
                                                    c -> {
                                                        final Collection<ScoreHolder> targets =
                                                            ScoreHolderArgument.getNamesWithDefaultWildcard(c, "targets");
                                                        return dispatch(
                                                            c.getSource(),
                                                            () -> enableTrigger(
                                                                c.getSource(),
                                                                targets,
                                                                ObjectiveArgument.getObjective(c, "objective")
                                                            )
                                                        );
                                                    }
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("display")
                                .then(
                                    Commands.literal("name")
                                        .then(
                                            Commands.argument("targets", ScoreHolderArgument.scoreHolders())
                                                .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                                .then(
                                                    Commands.argument("objective", ObjectiveArgument.objective())
                                                        .then(
                                                            Commands.argument("name", ComponentArgument.textComponent(context))
                                                                .executes(
                                                                    c -> {
                                                                        final Collection<ScoreHolder> targets =
                                                                            ScoreHolderArgument.getNamesWithDefaultWildcard(c, "targets");
                                                                        return dispatch(
                                                                            c.getSource(),
                                                                            () -> setScoreDisplay(
                                                                                c.getSource(),
                                                                                targets,
                                                                                ObjectiveArgument.getObjective(c, "objective"),
                                                                                ComponentArgument.getResolvedComponent(c, "name")
                                                                            )
                                                                        );
                                                                    }
                                                                )
                                                        )
                                                        .executes(
                                                            c -> {
                                                                final Collection<ScoreHolder> targets =
                                                                    ScoreHolderArgument.getNamesWithDefaultWildcard(c, "targets");
                                                                return dispatch(
                                                                    c.getSource(),
                                                                    () -> setScoreDisplay(
                                                                        c.getSource(),
                                                                        targets,
                                                                        ObjectiveArgument.getObjective(c, "objective"),
                                                                        null
                                                                    )
                                                                );
                                                            }
                                                        )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("numberformat")
                                        .then(
                                            Commands.argument("targets", ScoreHolderArgument.scoreHolders())
                                                .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                                .then(
                                                    addNumberFormats(
                                                        context,
                                                        Commands.argument("objective", ObjectiveArgument.objective()),
                                                        (c, format) -> {
                                                            final Collection<ScoreHolder> targets =
                                                                ScoreHolderArgument.getNamesWithDefaultWildcard(c, "targets");
                                                            return dispatch(
                                                                c.getSource(),
                                                                () -> setScoreNumberFormat(
                                                                    c.getSource(),
                                                                    targets,
                                                                    ObjectiveArgument.getObjective(c, "objective"),
                                                                    format
                                                                )
                                                            );
                                                        }
                                                    )
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("operation")
                                .then(
                                    Commands.argument("targets", ScoreHolderArgument.scoreHolders())
                                        .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                        .then(
                                            Commands.argument("targetObjective", ObjectiveArgument.objective())
                                                .then(
                                                    Commands.argument("operation", OperationArgument.operation())
                                                        .then(
                                                            Commands.argument("source", ScoreHolderArgument.scoreHolders())
                                                                .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                                                .then(
                                                                    Commands.argument("sourceObjective", ObjectiveArgument.objective())
                                                                        .executes(
                                                                            c -> {
                                                                                final Collection<ScoreHolder> targets =
                                                                                    ScoreHolderArgument.getNamesWithDefaultWildcard(c, "targets");
                                                                                final Collection<ScoreHolder> source =
                                                                                    ScoreHolderArgument.getNamesWithDefaultWildcard(c, "source");
                                                                                return dispatch(
                                                                                    c.getSource(),
                                                                                    () -> performOperation(
                                                                                        c.getSource(),
                                                                                        targets,
                                                                                        ObjectiveArgument.getWritableObjective(c, "targetObjective"),
                                                                                        OperationArgument.getOperation(c, "operation"),
                                                                                        source,
                                                                                        ObjectiveArgument.getObjective(c, "sourceObjective")
                                                                                    )
                                                                                );
                                                                            }
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

    // ----------------------------------------------------------------
    //  EXP5Plus: global-tick dispatch & feedback plumbing
    // ----------------------------------------------------------------

    /**
     * Dispatch a scoreboard task onto the Folia global tick thread.
     *
     * <p>Fast path: when the command already runs on the global tick thread
     * (console commands), the task executes inline and the exact result is
     * returned. Async path: the task is queued to the global tick queue, an
     * optimistic placeholder (1) is returned, and failures are reported back
     * on the source's region via {@code runOnSource} — matching the DataCommands
     * cross-region semantics.</p>
     *
     * <p>EXP6Plus: inside an EXP chain the registered global-tick future is
     * now genuinely awaited by the walker's suspend step (the EXP6Plus
     * walker transform drains {@code ctx.futures} into the suspend barrier),
     * so the next command block always sees the landed mutation.</p>
     */
    private static int dispatch(final CommandSourceStack source, final RegionCommandExecutor.BlockTask<Integer> task) {
        // EXP5Plus P2: propagate the EXP chain's PhaseSnapshot onto the global
        // tick thread, so the data-pool interception hooks in Scoreboard keep
        // recording score reads/writes into the same Phase (OCC capture).
        final com.azurebranches.command.PhaseSnapshot snap =
            com.azurebranches.command.ExpChainSupport.getPhaseSnapshot();
        final RegionCommandExecutor.BlockTask<Integer> wrapped = () -> {
            if (snap != null) {
                com.azurebranches.command.ExpChainSupport.setPhaseSnapshot(snap);
            }
            try {
                return task.run();
            } finally {
                if (snap != null) {
                    com.azurebranches.command.ExpChainSupport.clearPhaseSnapshot();
                }
            }
        };
        final CompletableFuture<Integer> future = RegionCommandExecutor.onGlobalAsync(wrapped);

        // EXP5Plus P2: register the global-tick future with the EXP chain
        // walker's receipt bag so the chain suspends until the scoreboard
        // mutation has actually landed (and the PhaseSnapshot capture happened)
        // before the next command block in the chain executes. No-op outside
        // EXP chain contexts (players/console).
        final CompletableFuture<Boolean> chainFuture =
            com.azurebranches.command.ExpChainSupport.registerRemote();
        if (chainFuture != null) {
            future.whenComplete((result, ex) -> chainFuture.complete(ex == null));
        }

        if (future.isDone()) {
            try {
                return future.join();
            } catch (final CompletionException e) {
                source.sendFailure(errorComponent(e));
                return 0;
            }
        }
        future.whenComplete((result, ex) -> RegionCommandExecutor.runOnSource(source, () -> {
            if (ex != null) {
                source.sendFailure(errorComponent(ex));
            }
        }));
        return 1; // optimistic placeholder; real feedback completes asynchronously
    }

    /**
     * Unwrap an async failure into its user-facing message, preserving
     * {@link CommandSyntaxException} localization (same helper as DataCommands).
     */
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

    /**
     * Resolve a score holder's feedback display name on its owning region.
     *
     * <p>Entity-backed holders ({@code @e} selectors, player names) hop to the
     * entity's region thread — the same pattern as the restored /tag command.
     * Name-only holders (text, {@code *}, game profiles) complete inline. The
     * callback always runs on the source's region thread.</p>
     */
    private static void withFeedbackName(final CommandSourceStack source, final ScoreHolder holder, final Consumer<Component> callback) {
        feedbackNameAsync(holder).thenAccept(name -> sendOnSource(source, () -> callback.accept(name)));
    }

    /**
     * Asynchronously resolve a holder's feedback display name on the owning
     * region thread. Failures fall back to the holder's scoreboard name
     * (an immutable string), never propagating an exception.
     */
    private static CompletableFuture<Component> feedbackNameAsync(final ScoreHolder holder) {
        if (holder instanceof Entity entity) {
            return RegionCommandExecutor.<Component>onEntityAsync(entity, e -> ((ScoreHolder) e).getFeedbackDisplayName())
                .handle((name, ex) -> (ex == null && name != null) ? name : Component.literal(holder.getScoreboardName()));
        }
        return CompletableFuture.completedFuture(holder.getFeedbackDisplayName());
    }

    /** A thread-safe name for error messages: entities use their scoreboard name (immutable). */
    private static Component safeHolderName(final ScoreHolder holder) {
        return holder instanceof Entity ? Component.literal(holder.getScoreboardName()) : holder.getFeedbackDisplayName();
    }

    /** Run feedback on the source's region (console sources run inline). */
    private static void sendOnSource(final CommandSourceStack source, final Runnable action) {
        RegionCommandExecutor.runOnSource(source, action);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> addNumberFormats(
        final CommandBuildContext context, final ArgumentBuilder<CommandSourceStack, ?> top, final ScoreboardCommand.NumberFormatCommandExecutor callback
    ) {
        return top.then(Commands.literal("blank").executes(c -> callback.run(c, BlankFormat.INSTANCE)))
            .then(Commands.literal("fixed").then(Commands.argument("contents", ComponentArgument.textComponent(context)).executes(c -> {
                Component contents = ComponentArgument.getResolvedComponent(c, "contents");
                return callback.run(c, new FixedFormat(contents));
            })))
            .then(Commands.literal("styled").then(Commands.argument("style", StyleArgument.style(context)).executes(c -> {
                Style style = StyleArgument.getStyle(c, "style");
                return callback.run(c, new StyledFormat(style));
            })))
            .executes(c -> callback.run(c, null));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createRenderTypeModify() {
        LiteralArgumentBuilder<CommandSourceStack> result = Commands.literal("rendertype");

        for (ObjectiveCriteria.RenderType renderType : ObjectiveCriteria.RenderType.values()) {
            result.then(
                Commands.literal(renderType.getId()).executes(
                    c -> dispatch(
                        c.getSource(),
                        () -> setRenderType(c.getSource(), ObjectiveArgument.getObjective(c, "objective"), renderType)
                    )
                )
            );
        }

        return result;
    }

    private static CompletableFuture<Suggestions> suggestTriggers(
        final CommandSourceStack source, final Collection<ScoreHolder> targets, final SuggestionsBuilder builder
    ) {
        // EXP5Plus: read-only suggestion lookup; runs on the suggestion thread
        // (accepted best-effort, same as the restored /trigger command).
        List<String> result = Lists.newArrayList();
        Scoreboard scoreboard = source.getServer().getScoreboard();

        for (Objective objective : scoreboard.getObjectives()) {
            if (objective.getCriteria() == ObjectiveCriteria.TRIGGER) {
                boolean available = false;

                for (ScoreHolder name : targets) {
                    ReadOnlyScoreInfo scoreInfo = scoreboard.getPlayerScoreInfo(name, objective);
                    if (scoreInfo == null || scoreInfo.isLocked()) {
                        available = true;
                        break;
                    }
                }

                if (available) {
                    result.add(objective.getName());
                }
            }
        }

        return SharedSuggestionProvider.suggest(result, builder);
    }

    private static int getScore(final CommandSourceStack source, final ScoreHolder target, final Objective objective) throws CommandSyntaxException {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        ReadOnlyScoreInfo score = scoreboard.getPlayerScoreInfo(target, objective);
        if (score == null) {
            throw ERROR_NO_VALUE.create(objective.getName(), safeHolderName(target));
        } else {
            final Component objectiveName = objective.getFormattedDisplayName();
            final int value = score.value();
            withFeedbackName(source, target, name ->
                sendOnSource(source, () -> source.sendSuccess(
                    () -> Component.translatable("commands.scoreboard.players.get.success", name, value, objectiveName), false
                ))
            );
            return value;
        }
    }

    private static int performOperation(
        final CommandSourceStack source,
        final Collection<ScoreHolder> targets,
        final Objective targetObjective,
        final OperationArgument.Operation operation,
        final Collection<ScoreHolder> sources,
        final Objective sourceObjective
    ) throws CommandSyntaxException {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        int result = 0;

        for (ScoreHolder target : targets) {
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(target, targetObjective);

            for (ScoreHolder from : sources) {
                ScoreAccess sourceScore = scoreboard.getOrCreatePlayerScore(from, sourceObjective);
                operation.apply(score, sourceScore);
            }

            result += score.get();
        }

        if (targets.size() == 1) {
            final ScoreHolder only = targets.iterator().next();
            final int finalResult = result;
            final Component objectiveName = targetObjective.getFormattedDisplayName();
            withFeedbackName(source, only, name ->
                sendOnSource(source, () -> source.sendSuccess(
                    () -> Component.translatable("commands.scoreboard.players.operation.success.single", objectiveName, name, finalResult), true
                ))
            );
        } else {
            final Component msg = Component.translatable("commands.scoreboard.players.operation.success.multiple", targetObjective.getFormattedDisplayName(), targets.size());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        }

        return result;
    }

    private static int enableTrigger(final CommandSourceStack source, final Collection<ScoreHolder> names, final Objective objective) throws CommandSyntaxException {
        if (objective.getCriteria() != ObjectiveCriteria.TRIGGER) {
            throw ERROR_NOT_TRIGGER.create();
        } else {
            Scoreboard scoreboard = source.getServer().getScoreboard();
            int count = 0;

            for (ScoreHolder name : names) {
                ScoreAccess score = scoreboard.getOrCreatePlayerScore(name, objective);
                if (score.locked()) {
                    score.unlock();
                    count++;
                }
            }

            if (count == 0) {
                throw ERROR_TRIGGER_ALREADY_ENABLED.create();
            } else {
                if (names.size() == 1) {
                    final ScoreHolder only = names.iterator().next();
                    final Component objectiveName = objective.getFormattedDisplayName();
                    withFeedbackName(source, only, name ->
                        sendOnSource(source, () -> source.sendSuccess(
                            () -> Component.translatable("commands.scoreboard.players.enable.success.single", objectiveName, name), true
                        ))
                    );
                } else {
                    final Component msg = Component.translatable("commands.scoreboard.players.enable.success.multiple", objective.getFormattedDisplayName(), names.size());
                    sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
                }

                return count;
            }
        }
    }

    private static int resetScores(final CommandSourceStack source, final Collection<ScoreHolder> names) {
        Scoreboard scoreboard = source.getServer().getScoreboard();

        for (ScoreHolder name : names) {
            scoreboard.resetAllPlayerScores(name);
        }

        if (names.size() == 1) {
            final ScoreHolder only = names.iterator().next();
            withFeedbackName(source, only, name ->
                sendOnSource(source, () -> source.sendSuccess(
                    () -> Component.translatable("commands.scoreboard.players.reset.all.single", name), true
                ))
            );
        } else {
            final Component msg = Component.translatable("commands.scoreboard.players.reset.all.multiple", names.size());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        }

        return names.size();
    }

    private static int resetScore(final CommandSourceStack source, final Collection<ScoreHolder> names, final Objective objective) {
        Scoreboard scoreboard = source.getServer().getScoreboard();

        for (ScoreHolder name : names) {
            scoreboard.resetSinglePlayerScore(name, objective);
        }

        if (names.size() == 1) {
            final ScoreHolder only = names.iterator().next();
            final Component objectiveName = objective.getFormattedDisplayName();
            withFeedbackName(source, only, name ->
                sendOnSource(source, () -> source.sendSuccess(
                    () -> Component.translatable("commands.scoreboard.players.reset.specific.single", objectiveName, name), true
                ))
            );
        } else {
            final Component msg = Component.translatable("commands.scoreboard.players.reset.specific.multiple", objective.getFormattedDisplayName(), names.size());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        }

        return names.size();
    }

    private static int setScore(final CommandSourceStack source, final Collection<ScoreHolder> names, final Objective objective, final int value) {
        Scoreboard scoreboard = source.getServer().getScoreboard();

        for (ScoreHolder name : names) {
            scoreboard.getOrCreatePlayerScore(name, objective).set(value);
        }

        if (names.size() == 1) {
            final ScoreHolder only = names.iterator().next();
            final Component objectiveName = objective.getFormattedDisplayName();
            withFeedbackName(source, only, name ->
                sendOnSource(source, () -> source.sendSuccess(
                    () -> Component.translatable("commands.scoreboard.players.set.success.single", objectiveName, name, value), true
                ))
            );
        } else {
            final Component msg = Component.translatable("commands.scoreboard.players.set.success.multiple", objective.getFormattedDisplayName(), names.size(), value);
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        }

        return value * names.size();
    }

    private static int setScoreDisplay(
        final CommandSourceStack source, final Collection<ScoreHolder> names, final Objective objective, final @Nullable Component display
    ) {
        Scoreboard scoreboard = source.getServer().getScoreboard();

        for (ScoreHolder name : names) {
            scoreboard.getOrCreatePlayerScore(name, objective).display(display);
        }

        if (names.size() == 1) {
            final ScoreHolder only = names.iterator().next();
            final Component objectiveName = objective.getFormattedDisplayName();
            final boolean clearing = display == null;
            withFeedbackName(source, only, name -> {
                final Component msg = clearing
                    ? Component.translatable("commands.scoreboard.players.display.name.clear.success.single", name, objectiveName)
                    : Component.translatable("commands.scoreboard.players.display.name.set.success.single", display, name, objectiveName);
                sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
            });
        } else {
            final Component msg = display == null
                ? Component.translatable("commands.scoreboard.players.display.name.clear.success.multiple", names.size(), objective.getFormattedDisplayName())
                : Component.translatable("commands.scoreboard.players.display.name.set.success.multiple", display, names.size(), objective.getFormattedDisplayName());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        }

        return names.size();
    }

    private static int setScoreNumberFormat(
        final CommandSourceStack source, final Collection<ScoreHolder> names, final Objective objective, final @Nullable NumberFormat numberFormat
    ) {
        Scoreboard scoreboard = source.getServer().getScoreboard();

        for (ScoreHolder name : names) {
            scoreboard.getOrCreatePlayerScore(name, objective).numberFormatOverride(numberFormat);
        }

        if (names.size() == 1) {
            final ScoreHolder only = names.iterator().next();
            final Component objectiveName = objective.getFormattedDisplayName();
            final boolean clearing = numberFormat == null;
            withFeedbackName(source, only, name -> {
                final Component msg = clearing
                    ? Component.translatable("commands.scoreboard.players.display.numberFormat.clear.success.single", name, objectiveName)
                    : Component.translatable("commands.scoreboard.players.display.numberFormat.set.success.single", name, objectiveName);
                sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
            });
        } else {
            final Component msg = numberFormat == null
                ? Component.translatable("commands.scoreboard.players.display.numberFormat.clear.success.multiple", names.size(), objective.getFormattedDisplayName())
                : Component.translatable("commands.scoreboard.players.display.numberFormat.set.success.multiple", names.size(), objective.getFormattedDisplayName());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        }

        return names.size();
    }

    private static int addScore(final CommandSourceStack source, final Collection<ScoreHolder> names, final Objective objective, final int value) {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        int result = 0;

        for (ScoreHolder name : names) {
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(name, objective);
            score.set(score.get() + value);
            result += score.get();
        }

        if (names.size() == 1) {
            final ScoreHolder only = names.iterator().next();
            final int finalResult = result;
            final Component objectiveName = objective.getFormattedDisplayName();
            withFeedbackName(source, only, name ->
                sendOnSource(source, () -> source.sendSuccess(
                    () -> Component.translatable("commands.scoreboard.players.add.success.single", value, objectiveName, name, finalResult), true
                ))
            );
        } else {
            final Component msg = Component.translatable("commands.scoreboard.players.add.success.multiple", value, objective.getFormattedDisplayName(), names.size());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        }

        return result;
    }

    private static int removeScore(final CommandSourceStack source, final Collection<ScoreHolder> names, final Objective objective, final int value) {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        int result = 0;

        for (ScoreHolder name : names) {
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(name, objective);
            score.set(score.get() - value);
            result += score.get();
        }

        if (names.size() == 1) {
            final ScoreHolder only = names.iterator().next();
            final int finalResult = result;
            final Component objectiveName = objective.getFormattedDisplayName();
            withFeedbackName(source, only, name ->
                sendOnSource(source, () -> source.sendSuccess(
                    () -> Component.translatable("commands.scoreboard.players.remove.success.single", value, objectiveName, name, finalResult), true
                ))
            );
        } else {
            final Component msg = Component.translatable("commands.scoreboard.players.remove.success.multiple", value, objective.getFormattedDisplayName(), names.size());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        }

        return result;
    }

    private static int listTrackedPlayers(final CommandSourceStack source) {
        Collection<ScoreHolder> entities = source.getServer().getScoreboard().getTrackedPlayers();
        if (entities.isEmpty()) {
            final Component msg = Component.translatable("commands.scoreboard.players.list.empty");
            sendOnSource(source, () -> source.sendSuccess(() -> msg, false));
        } else {
            // EXP5Plus: resolve every tracked holder's feedback name on its own
            // region before composing the list (entity-backed holders).
            final List<CompletableFuture<Component>> nameFutures = new ArrayList<>(entities.size());
            for (final ScoreHolder holder : entities) {
                nameFutures.add(feedbackNameAsync(holder));
            }
            CompletableFuture.allOf(nameFutures.toArray(new CompletableFuture[0])).whenComplete((v, ex) -> {
                final List<Component> names = new ArrayList<>(nameFutures.size());
                for (final CompletableFuture<Component> future : nameFutures) {
                    names.add(future.join());
                }
                final Component msg = Component.translatable(
                    "commands.scoreboard.players.list.success", entities.size(), ComponentUtils.formatList(names, c -> c)
                );
                sendOnSource(source, () -> source.sendSuccess(() -> msg, false));
            });
        }

        return entities.size();
    }

    private static int listTrackedPlayerScores(final CommandSourceStack source, final ScoreHolder entity) {
        Object2IntMap<Objective> scores = source.getServer().getScoreboard().listPlayerScores(entity);
        final List<Component> entries = new ArrayList<>(scores.size());
        Object2IntMaps.fastForEach(
            scores,
            entry -> entries.add(
                Component.translatable(
                    "commands.scoreboard.players.list.entity.entry", ((Objective)entry.getKey()).getFormattedDisplayName(), entry.getIntValue()
                )
            )
        );
        if (scores.isEmpty()) {
            withFeedbackName(source, entity, name ->
                sendOnSource(source, () -> source.sendSuccess(
                    () -> Component.translatable("commands.scoreboard.players.list.entity.empty", name), false
                ))
            );
        } else {
            withFeedbackName(source, entity, name ->
                sendOnSource(source, () -> {
                    source.sendSuccess(
                        () -> Component.translatable("commands.scoreboard.players.list.entity.success", name, scores.size()), false
                    );
                    for (final Component entry : entries) {
                        source.sendSuccess(() -> entry, false);
                    }
                })
            );
        }

        return scores.size();
    }

    private static int clearDisplaySlot(final CommandSourceStack source, final DisplaySlot slot) throws CommandSyntaxException {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        if (scoreboard.getDisplayObjective(slot) == null) {
            throw ERROR_DISPLAY_SLOT_ALREADY_EMPTY.create();
        } else {
            scoreboard.setDisplayObjective(slot, null);
            final Component msg = Component.translatable("commands.scoreboard.objectives.display.cleared", slot.getSerializedName());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
            return 0;
        }
    }

    private static int setDisplaySlot(final CommandSourceStack source, final DisplaySlot slot, final Objective objective) throws CommandSyntaxException {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        if (scoreboard.getDisplayObjective(slot) == objective) {
            throw ERROR_DISPLAY_SLOT_ALREADY_SET.create();
        } else {
            scoreboard.setDisplayObjective(slot, objective);
            final Component msg = Component.translatable("commands.scoreboard.objectives.display.set", slot.getSerializedName(), objective.getDisplayName());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
            return 0;
        }
    }

    private static int setDisplayName(final CommandSourceStack source, final Objective objective, final Component displayName) {
        if (!objective.getDisplayName().equals(displayName)) {
            objective.setDisplayName(displayName);
            final Component msg = Component.translatable("commands.scoreboard.objectives.modify.displayname", objective.getName(), objective.getFormattedDisplayName());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        }

        return 0;
    }

    private static int setDisplayAutoUpdate(final CommandSourceStack source, final Objective objective, final boolean displayAutoUpdate) {
        if (objective.displayAutoUpdate() != displayAutoUpdate) {
            objective.setDisplayAutoUpdate(displayAutoUpdate);
            final Component msg = displayAutoUpdate
                ? Component.translatable("commands.scoreboard.objectives.modify.displayAutoUpdate.enable", objective.getName(), objective.getFormattedDisplayName())
                : Component.translatable("commands.scoreboard.objectives.modify.displayAutoUpdate.disable", objective.getName(), objective.getFormattedDisplayName());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        }

        return 0;
    }

    private static int setObjectiveFormat(final CommandSourceStack source, final Objective objective, final @Nullable NumberFormat numberFormat) {
        objective.setNumberFormat(numberFormat);
        final Component msg = numberFormat != null
            ? Component.translatable("commands.scoreboard.objectives.modify.objectiveFormat.set", objective.getName())
            : Component.translatable("commands.scoreboard.objectives.modify.objectiveFormat.clear", objective.getName());
        sendOnSource(source, () -> source.sendSuccess(() -> msg, true));

        return 0;
    }

    private static int setRenderType(final CommandSourceStack source, final Objective objective, final ObjectiveCriteria.RenderType renderType) {
        if (objective.getRenderType() != renderType) {
            objective.setRenderType(renderType);
            final Component msg = Component.translatable("commands.scoreboard.objectives.modify.rendertype", objective.getFormattedDisplayName());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        }

        return 0;
    }

    private static int removeObjective(final CommandSourceStack source, final Objective objective) {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        scoreboard.removeObjective(objective);
        final Component msg = Component.translatable("commands.scoreboard.objectives.remove.success", objective.getFormattedDisplayName());
        sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
        return scoreboard.getObjectives().size();
    }

    private static int addObjective(final CommandSourceStack source, final String name, final ObjectiveCriteria criteria, final Component displayName) throws CommandSyntaxException {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        if (scoreboard.getObjective(name) != null) {
            throw ERROR_OBJECTIVE_ALREADY_EXISTS.create();
        } else {
            scoreboard.addObjective(name, criteria, displayName, criteria.getDefaultRenderType(), false, null);
            Objective objective = scoreboard.getObjective(name);
            final Component msg = Component.translatable("commands.scoreboard.objectives.add.success", objective.getFormattedDisplayName());
            sendOnSource(source, () -> source.sendSuccess(() -> msg, true));
            return scoreboard.getObjectives().size();
        }
    }

    private static int listObjectives(final CommandSourceStack source) {
        Collection<Objective> objectives = source.getServer().getScoreboard().getObjectives();
        if (objectives.isEmpty()) {
            final Component msg = Component.translatable("commands.scoreboard.objectives.list.empty");
            sendOnSource(source, () -> source.sendSuccess(() -> msg, false));
        } else {
            final Component msg = Component.translatable("commands.scoreboard.objectives.list.success", objectives.size(), ComponentUtils.formatList(objectives, Objective::getFormattedDisplayName));
            sendOnSource(source, () -> source.sendSuccess(() -> msg, false));
        }

        return objectives.size();
    }

    @FunctionalInterface
    public interface NumberFormatCommandExecutor {
        int run(CommandContext<CommandSourceStack> context, @Nullable NumberFormat format) throws CommandSyntaxException;
    }
}
