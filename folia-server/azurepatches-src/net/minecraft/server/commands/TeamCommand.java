package net.minecraft.server.commands;

import com.google.common.collect.Lists;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ColorArgument;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.commands.arguments.ScoreHolderArgument;
import net.minecraft.commands.arguments.TeamArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

/**
 * AzureBranches EXP7Plus: /team restored for Folia region threading.
 *
 * <p>Folia upstream commented out {@code TeamCommand.register} (region threading)
 * because team operations mutate the server-global scoreboard. Mirroring the
 * restored {@link ScoreboardCommand}, every terminal handler is dispatched onto
 * the Folia global tick thread via {@link #dispatch}; feedback is routed back to
 * the source's region through {@link #feedback} (console sources inline).</p>
 */
public class TeamCommand {
    private static final SimpleCommandExceptionType ERROR_TEAM_ALREADY_EXISTS = new SimpleCommandExceptionType(
        Component.translatable("commands.team.add.duplicate")
    );
    private static final SimpleCommandExceptionType ERROR_TEAM_ALREADY_EMPTY = new SimpleCommandExceptionType(
        Component.translatable("commands.team.empty.unchanged")
    );
    private static final SimpleCommandExceptionType ERROR_TEAM_ALREADY_NAME = new SimpleCommandExceptionType(
        Component.translatable("commands.team.option.name.unchanged")
    );
    private static final SimpleCommandExceptionType ERROR_TEAM_ALREADY_COLOR = new SimpleCommandExceptionType(
        Component.translatable("commands.team.option.color.unchanged")
    );
    private static final SimpleCommandExceptionType ERROR_TEAM_ALREADY_FRIENDLYFIRE_ENABLED = new SimpleCommandExceptionType(
        Component.translatable("commands.team.option.friendlyfire.alreadyEnabled")
    );
    private static final SimpleCommandExceptionType ERROR_TEAM_ALREADY_FRIENDLYFIRE_DISABLED = new SimpleCommandExceptionType(
        Component.translatable("commands.team.option.friendlyfire.alreadyDisabled")
    );
    private static final SimpleCommandExceptionType ERROR_TEAM_ALREADY_FRIENDLYINVISIBLES_ENABLED = new SimpleCommandExceptionType(
        Component.translatable("commands.team.option.seeFriendlyInvisibles.alreadyEnabled")
    );
    private static final SimpleCommandExceptionType ERROR_TEAM_ALREADY_FRIENDLYINVISIBLES_DISABLED = new SimpleCommandExceptionType(
        Component.translatable("commands.team.option.seeFriendlyInvisibles.alreadyDisabled")
    );
    private static final SimpleCommandExceptionType ERROR_TEAM_NAMETAG_VISBILTY_UNCHANGED = new SimpleCommandExceptionType(
        Component.translatable("commands.team.option.nametagVisibility.unchanged")
    );
    private static final SimpleCommandExceptionType ERROR_TEAM_DEATH_MESSAGE_VISBILTY_UNCHANGED = new SimpleCommandExceptionType(
        Component.translatable("commands.team.option.deathMessageVisibility.unchanged")
    );
    private static final SimpleCommandExceptionType ERROR_TEAM_COLLISION_UNCHANGED = new SimpleCommandExceptionType(
        Component.translatable("commands.team.option.collisionRule.unchanged")
    );

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext context) {
        dispatcher.register(
            Commands.literal("team")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("list")
                        .executes(c -> dispatch(c.getSource(), () -> listTeams(c.getSource())))
                        .then(Commands.argument("team", TeamArgument.team()).executes(c -> dispatch(c.getSource(), () -> listMembers(c.getSource(), TeamArgument.getTeam(c, "team")))))
                )
                .then(
                    Commands.literal("add")
                        .then(
                            Commands.argument("team", StringArgumentType.word())
                                .executes(c -> dispatch(c.getSource(), () -> createTeam(c.getSource(), StringArgumentType.getString(c, "team"))))
                                .then(
                                    Commands.argument("displayName", ComponentArgument.textComponent(context))
                                        .executes(
                                            c -> dispatch(
                                                c.getSource(),
                                                () -> createTeam(
                                                    c.getSource(),
                                                    StringArgumentType.getString(c, "team"),
                                                    ComponentArgument.getResolvedComponent(c, "displayName")
                                                )
                                            )
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("remove")
                        .then(Commands.argument("team", TeamArgument.team()).executes(c -> dispatch(c.getSource(), () -> deleteTeam(c.getSource(), TeamArgument.getTeam(c, "team")))))
                )
                .then(
                    Commands.literal("empty")
                        .then(Commands.argument("team", TeamArgument.team()).executes(c -> dispatch(c.getSource(), () -> emptyTeam(c.getSource(), TeamArgument.getTeam(c, "team")))))
                )
                .then(
                    Commands.literal("join")
                        .then(
                            Commands.argument("team", TeamArgument.team())
                                .executes(
                                    c -> dispatch(
                                        c.getSource(),
                                        () -> joinTeam(c.getSource(), TeamArgument.getTeam(c, "team"), Collections.singleton(c.getSource().getEntityOrException()))
                                    )
                                )
                                .then(
                                    Commands.argument("members", ScoreHolderArgument.scoreHolders())
                                        .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                        .executes(
                                            c -> dispatch(
                                                c.getSource(),
                                                () -> joinTeam(
                                                    c.getSource(), TeamArgument.getTeam(c, "team"), ScoreHolderArgument.getNamesWithDefaultWildcard(c, "members")
                                                )
                                            )
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("leave")
                        .then(
                            Commands.argument("members", ScoreHolderArgument.scoreHolders())
                                .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                .executes(c -> dispatch(c.getSource(), () -> leaveTeam(c.getSource(), ScoreHolderArgument.getNamesWithDefaultWildcard(c, "members"))))
                        )
                )
                .then(
                    Commands.literal("modify")
                        .then(
                            Commands.argument("team", TeamArgument.team())
                                .then(
                                    Commands.literal("displayName")
                                        .then(
                                            Commands.argument("displayName", ComponentArgument.textComponent(context))
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> setDisplayName(
                                                            c.getSource(),
                                                            TeamArgument.getTeam(c, "team"),
                                                            ComponentArgument.getResolvedComponent(c, "displayName")
                                                        )
                                                    )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("color")
                                        .then(
                                            Commands.argument("value", ColorArgument.color())
                                                .executes(c -> dispatch(c.getSource(), () -> setColor(c.getSource(), TeamArgument.getTeam(c, "team"), ColorArgument.getColor(c, "value"))))
                                        )
                                )
                                .then(
                                    Commands.literal("friendlyFire")
                                        .then(
                                            Commands.argument("allowed", BoolArgumentType.bool())
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(), () -> setFriendlyFire(c.getSource(), TeamArgument.getTeam(c, "team"), BoolArgumentType.getBool(c, "allowed"))
                                                    )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("seeFriendlyInvisibles")
                                        .then(
                                            Commands.argument("allowed", BoolArgumentType.bool())
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> setFriendlySight(
                                                            c.getSource(), TeamArgument.getTeam(c, "team"), BoolArgumentType.getBool(c, "allowed")
                                                        )
                                                    )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("nametagVisibility")
                                        .then(
                                            Commands.literal("never")
                                                .executes(c -> dispatch(c.getSource(), () -> setNametagVisibility(c.getSource(), TeamArgument.getTeam(c, "team"), Team.Visibility.NEVER)))
                                        )
                                        .then(
                                            Commands.literal("hideForOtherTeams")
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> setNametagVisibility(
                                                            c.getSource(), TeamArgument.getTeam(c, "team"), Team.Visibility.HIDE_FOR_OTHER_TEAMS
                                                        )
                                                    )
                                                )
                                        )
                                        .then(
                                            Commands.literal("hideForOwnTeam")
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> setNametagVisibility(c.getSource(), TeamArgument.getTeam(c, "team"), Team.Visibility.HIDE_FOR_OWN_TEAM)
                                                    )
                                                )
                                        )
                                        .then(
                                            Commands.literal("always")
                                                .executes(c -> dispatch(c.getSource(), () -> setNametagVisibility(c.getSource(), TeamArgument.getTeam(c, "team"), Team.Visibility.ALWAYS)))
                                        )
                                )
                                .then(
                                    Commands.literal("deathMessageVisibility")
                                        .then(
                                            Commands.literal("never")
                                                .executes(c -> dispatch(c.getSource(), () -> setDeathMessageVisibility(c.getSource(), TeamArgument.getTeam(c, "team"), Team.Visibility.NEVER)))
                                        )
                                        .then(
                                            Commands.literal("hideForOtherTeams")
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> setDeathMessageVisibility(
                                                            c.getSource(), TeamArgument.getTeam(c, "team"), Team.Visibility.HIDE_FOR_OTHER_TEAMS
                                                        )
                                                    )
                                                )
                                        )
                                        .then(
                                            Commands.literal("hideForOwnTeam")
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> setDeathMessageVisibility(
                                                            c.getSource(), TeamArgument.getTeam(c, "team"), Team.Visibility.HIDE_FOR_OWN_TEAM
                                                        )
                                                    )
                                                )
                                        )
                                        .then(
                                            Commands.literal("always")
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> setDeathMessageVisibility(c.getSource(), TeamArgument.getTeam(c, "team"), Team.Visibility.ALWAYS)
                                                    )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("collisionRule")
                                        .then(
                                            Commands.literal("never")
                                                .executes(c -> dispatch(c.getSource(), () -> setCollision(c.getSource(), TeamArgument.getTeam(c, "team"), Team.CollisionRule.NEVER)))
                                        )
                                        .then(
                                            Commands.literal("pushOwnTeam")
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(), () -> setCollision(c.getSource(), TeamArgument.getTeam(c, "team"), Team.CollisionRule.PUSH_OWN_TEAM)
                                                    )
                                                )
                                        )
                                        .then(
                                            Commands.literal("pushOtherTeams")
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> setCollision(c.getSource(), TeamArgument.getTeam(c, "team"), Team.CollisionRule.PUSH_OTHER_TEAMS)
                                                    )
                                                )
                                        )
                                        .then(
                                            Commands.literal("always")
                                                .executes(c -> dispatch(c.getSource(), () -> setCollision(c.getSource(), TeamArgument.getTeam(c, "team"), Team.CollisionRule.ALWAYS)))
                                        )
                                )
                                .then(
                                    Commands.literal("prefix")
                                        .then(
                                            Commands.argument("prefix", ComponentArgument.textComponent(context))
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> setPrefix(
                                                            c.getSource(), TeamArgument.getTeam(c, "team"), ComponentArgument.getResolvedComponent(c, "prefix")
                                                        )
                                                    )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("suffix")
                                        .then(
                                            Commands.argument("suffix", ComponentArgument.textComponent(context))
                                                .executes(
                                                    c -> dispatch(
                                                        c.getSource(),
                                                        () -> setSuffix(
                                                            c.getSource(), TeamArgument.getTeam(c, "team"), ComponentArgument.getResolvedComponent(c, "suffix")
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
    //  AzureBranches: global-tick dispatch & feedback plumbing (EXP7Plus)
    // ----------------------------------------------------------------

    /**
     * Dispatch a team task onto the Folia global tick thread (the scoreboard is
     * server-global data). Fast path: when the command already runs on the
     * global tick thread (console commands) the task runs inline. Mirrors
     * {@link ScoreboardCommand#dispatch} including the EXP-chain suspension.
     */
    private static int dispatch(final CommandSourceStack source, final RegionCommandExecutor.BlockTask<Integer> task) {
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

    /** Unwrap an async failure into its user-facing message (same as ScoreboardCommand). */
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

    /** Route scoreboard feedback onto the source's region (console sources inline). */
    private static void feedback(final CommandSourceStack source, final Supplier<Component> message, final boolean broadcast) {
        RegionCommandExecutor.runOnSource(source, () -> source.sendSuccess(message, broadcast));
    }

    private static Component getFirstMemberName(final Collection<ScoreHolder> members) {
        return members.iterator().next().getFeedbackDisplayName();
    }

    private static int leaveTeam(final CommandSourceStack source, final Collection<ScoreHolder> members) {
        Scoreboard scoreboard = source.getServer().getScoreboard();

        for (ScoreHolder member : members) {
            scoreboard.removePlayerFromTeam(member.getScoreboardName());
        }

        if (members.size() == 1) {
            feedback(source, () -> Component.translatable("commands.team.leave.success.single", getFirstMemberName(members)), true);
        } else {
            feedback(source, () -> Component.translatable("commands.team.leave.success.multiple", members.size()), true);
        }

        return members.size();
    }

    private static int joinTeam(final CommandSourceStack source, final PlayerTeam team, final Collection<ScoreHolder> members) {
        Scoreboard scoreboard = source.getServer().getScoreboard();

        for (ScoreHolder member : members) {
            scoreboard.addPlayerToTeam(member.getScoreboardName(), team);
        }

        if (members.size() == 1) {
            feedback(
                source, () -> Component.translatable("commands.team.join.success.single", getFirstMemberName(members), team.getFormattedDisplayName()), true
            );
        } else {
            feedback(source, () -> Component.translatable("commands.team.join.success.multiple", members.size(), team.getFormattedDisplayName()), true);
        }

        return members.size();
    }

    private static int setNametagVisibility(final CommandSourceStack source, final PlayerTeam team, final Team.Visibility visibility) throws CommandSyntaxException {
        if (team.getNameTagVisibility() == visibility) {
            throw ERROR_TEAM_NAMETAG_VISBILTY_UNCHANGED.create();
        } else {
            team.setNameTagVisibility(visibility);
            feedback(
                source, () -> Component.translatable("commands.team.option.nametagVisibility.success", team.getFormattedDisplayName(), visibility.getDisplayName()),
                true
            );
            return 0;
        }
    }

    private static int setDeathMessageVisibility(final CommandSourceStack source, final PlayerTeam team, final Team.Visibility visibility) throws CommandSyntaxException {
        if (team.getDeathMessageVisibility() == visibility) {
            throw ERROR_TEAM_DEATH_MESSAGE_VISBILTY_UNCHANGED.create();
        } else {
            team.setDeathMessageVisibility(visibility);
            feedback(
                source, () -> Component.translatable("commands.team.option.deathMessageVisibility.success", team.getFormattedDisplayName(), visibility.getDisplayName()),
                true
            );
            return 0;
        }
    }

    private static int setCollision(final CommandSourceStack source, final PlayerTeam team, final Team.CollisionRule collision) throws CommandSyntaxException {
        if (team.getCollisionRule() == collision) {
            throw ERROR_TEAM_COLLISION_UNCHANGED.create();
        } else {
            team.setCollisionRule(collision);
            feedback(
                source, () -> Component.translatable("commands.team.option.collisionRule.success", team.getFormattedDisplayName(), collision.getDisplayName()), true
            );
            return 0;
        }
    }

    private static int setFriendlySight(final CommandSourceStack source, final PlayerTeam team, final boolean allowed) throws CommandSyntaxException {
        if (team.canSeeFriendlyInvisibles() == allowed) {
            if (allowed) {
                throw ERROR_TEAM_ALREADY_FRIENDLYINVISIBLES_ENABLED.create();
            } else {
                throw ERROR_TEAM_ALREADY_FRIENDLYINVISIBLES_DISABLED.create();
            }
        } else {
            team.setSeeFriendlyInvisibles(allowed);
            feedback(
                source, () -> Component.translatable("commands.team.option.seeFriendlyInvisibles." + (allowed ? "enabled" : "disabled"), team.getFormattedDisplayName()),
                true
            );
            return 0;
        }
    }

    private static int setFriendlyFire(final CommandSourceStack source, final PlayerTeam team, final boolean allowed) throws CommandSyntaxException {
        if (team.isAllowFriendlyFire() == allowed) {
            if (allowed) {
                throw ERROR_TEAM_ALREADY_FRIENDLYFIRE_ENABLED.create();
            } else {
                throw ERROR_TEAM_ALREADY_FRIENDLYFIRE_DISABLED.create();
            }
        } else {
            team.setAllowFriendlyFire(allowed);
            feedback(
                source, () -> Component.translatable("commands.team.option.friendlyfire." + (allowed ? "enabled" : "disabled"), team.getFormattedDisplayName()), true
            );
            return 0;
        }
    }

    private static int setDisplayName(final CommandSourceStack source, final PlayerTeam team, final Component displayName) throws CommandSyntaxException {
        if (team.getDisplayName().equals(displayName)) {
            throw ERROR_TEAM_ALREADY_NAME.create();
        } else {
            team.setDisplayName(displayName);
            feedback(source, () -> Component.translatable("commands.team.option.name.success", team.getFormattedDisplayName()), true);
            return 0;
        }
    }

    private static int setColor(final CommandSourceStack source, final PlayerTeam team, final ChatFormatting color) throws CommandSyntaxException {
        if (team.getColor() == color) {
            throw ERROR_TEAM_ALREADY_COLOR.create();
        } else {
            team.setColor(color);
            feedback(source, () -> Component.translatable("commands.team.option.color.success", team.getFormattedDisplayName(), color.getName()), true);
            return 0;
        }
    }

    private static int emptyTeam(final CommandSourceStack source, final PlayerTeam team) throws CommandSyntaxException {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        Collection<String> members = Lists.newArrayList(team.getPlayers());
        if (members.isEmpty()) {
            throw ERROR_TEAM_ALREADY_EMPTY.create();
        } else {
            for (String member : members) {
                scoreboard.removePlayerFromTeam(member, team);
            }

            feedback(source, () -> Component.translatable("commands.team.empty.success", members.size(), team.getFormattedDisplayName()), true);
            return members.size();
        }
    }

    private static int deleteTeam(final CommandSourceStack source, final PlayerTeam team) {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        scoreboard.removePlayerTeam(team);
        feedback(source, () -> Component.translatable("commands.team.remove.success", team.getFormattedDisplayName()), true);
        return scoreboard.getPlayerTeams().size();
    }

    private static int createTeam(final CommandSourceStack source, final String name) throws CommandSyntaxException {
        return createTeam(source, name, Component.literal(name));
    }

    private static int createTeam(final CommandSourceStack source, final String name, final Component displayName) throws CommandSyntaxException {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        if (scoreboard.getPlayerTeam(name) != null) {
            throw ERROR_TEAM_ALREADY_EXISTS.create();
        } else {
            PlayerTeam team = scoreboard.addPlayerTeam(name);
            team.setDisplayName(displayName);
            feedback(source, () -> Component.translatable("commands.team.add.success", team.getFormattedDisplayName()), true);
            return scoreboard.getPlayerTeams().size();
        }
    }

    private static int listMembers(final CommandSourceStack source, final PlayerTeam team) {
        Collection<String> members = team.getPlayers();
        if (members.isEmpty()) {
            feedback(source, () -> Component.translatable("commands.team.list.members.empty", team.getFormattedDisplayName()), false);
        } else {
            feedback(
                source,
                () -> Component.translatable(
                    "commands.team.list.members.success", team.getFormattedDisplayName(), members.size(), ComponentUtils.formatList(members)
                ),
                false
            );
        }

        return members.size();
    }

    private static int listTeams(final CommandSourceStack source) {
        Collection<PlayerTeam> teams = source.getServer().getScoreboard().getPlayerTeams();
        if (teams.isEmpty()) {
            feedback(source, () -> Component.translatable("commands.team.list.teams.empty"), false);
        } else {
            feedback(
                source,
                () -> Component.translatable(
                    "commands.team.list.teams.success", teams.size(), ComponentUtils.formatList(teams, PlayerTeam::getFormattedDisplayName)
                ),
                false
            );
        }

        return teams.size();
    }

    private static int setPrefix(final CommandSourceStack source, final PlayerTeam team, final Component prefix) {
        team.setPlayerPrefix(prefix);
        feedback(source, () -> Component.translatable("commands.team.option.prefix.success", prefix), false);
        return 1;
    }

    private static int setSuffix(final CommandSourceStack source, final PlayerTeam team, final Component suffix) {
        team.setPlayerSuffix(suffix);
        feedback(source, () -> Component.translatable("commands.team.option.suffix.success", suffix), false);
        return 1;
    }
}
