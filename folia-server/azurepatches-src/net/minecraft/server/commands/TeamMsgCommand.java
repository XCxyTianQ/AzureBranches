package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;

/**
 * AzureBranches EXP7Plus: /teammsg restored for Folia region threading.
 *
 * <p>Folia upstream disabled {@code TeamMsgCommand.register} (region threading).
 * The team-membership reads ({@code entity.getTeam()}, {@code receiver.getTeam()})
 * and the playerlist iteration touch server-global scoreboard data, so the whole
 * executes body is dispatched onto the global tick thread (mirroring
 * {@link TeamCommand}); the actual message delivery runs through the normal
 * async chat-decoration chain exactly like vanilla.</p>
 */
public class TeamMsgCommand {
    private static final Style SUGGEST_STYLE = Style.EMPTY
        .withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.type.team.hover")))
        .withClickEvent(new ClickEvent.SuggestCommand("/teammsg "));
    private static final SimpleCommandExceptionType ERROR_NOT_ON_TEAM = new SimpleCommandExceptionType(Component.translatable("commands.teammsg.failed.noteam"));

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> msg = dispatcher.register(
            Commands.literal("teammsg")
                .then(
                    Commands.argument("message", MessageArgument.message())
                        .executes(
                            c -> dispatch(
                                c.getSource(),
                                () -> {
                                    CommandSourceStack source = c.getSource();
                                    Entity entity = source.getEntityOrException();
                                    PlayerTeam team = entity.getTeam();
                                    if (team == null) {
                                        throw ERROR_NOT_ON_TEAM.create();
                                    } else {
                                        List<ServerPlayer> receivers = source.getServer()
                                            .getPlayerList()
                                            .getPlayers()
                                            .stream()
                                            .filter(receiver -> receiver == entity || receiver.getTeam() == team)
                                            .toList();
                                        if (!receivers.isEmpty()) {
                                            final CommandContext<CommandSourceStack> ctx = c;
                                            MessageArgument.resolveChatMessage(ctx, "message", message -> sendMessage(source, entity, team, receivers, message));
                                        }

                                        return receivers.size();
                                    }
                                }
                            )
                        )
                )
        );
        dispatcher.register(Commands.literal("tm").redirect(msg));
    }

    // ----------------------------------------------------------------
    //  AzureBranches: global-tick dispatch & feedback plumbing (EXP7Plus)
    // ----------------------------------------------------------------

    /**
     * Dispatch a team-msg task onto the Folia global tick thread (team
     * membership is read from the server-global scoreboard). Fast path when
     * already on the global tick thread (console); mirrors TeamCommand.
     */
    private static int dispatch(final CommandSourceStack source, final RegionCommandExecutor.BlockTask<Integer> task) {
        final CompletableFuture<Integer> future = RegionCommandExecutor.onGlobalAsync(task);

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

    /** Unwrap an async failure into its user-facing message (same as TeamCommand). */
    private static Component errorComponent(final Throwable ex) {
        Throwable cause = ex;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof CommandSyntaxException cse) {
            return net.minecraft.network.chat.ComponentUtils.fromMessage(cse.getRawMessage());
        }
        return Component.translatable("command.failed");
    }

    private static void sendMessage(
        final CommandSourceStack source, final Entity entity, final PlayerTeam team, final List<ServerPlayer> receivers, final PlayerChatMessage message
    ) {
        Component teamName = team.getFormattedDisplayName().withStyle(SUGGEST_STYLE);
        ChatType.Bound incomingChatType = ChatType.bind(ChatType.TEAM_MSG_COMMAND_INCOMING, source).withTargetName(teamName);
        ChatType.Bound outgoingChatType = ChatType.bind(ChatType.TEAM_MSG_COMMAND_OUTGOING, source).withTargetName(teamName);
        OutgoingChatMessage tracked = OutgoingChatMessage.create(message);
        boolean wasFullyFiltered = false;

        for (ServerPlayer teamPlayer : receivers) {
            ChatType.Bound chatType = teamPlayer == entity ? outgoingChatType : incomingChatType;
            boolean filtered = source.shouldFilterMessageTo(teamPlayer);
            teamPlayer.sendChatMessage(tracked, filtered, chatType);
            wasFullyFiltered |= filtered && message.isFullyFiltered();
        }

        if (wasFullyFiltered) {
            source.sendSystemMessage(PlayerList.CHAT_FILTERED_FULL);
        }
    }
}
