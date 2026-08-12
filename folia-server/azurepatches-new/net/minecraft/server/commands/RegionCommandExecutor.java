package net.minecraft.server.commands;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * AzureBranches - cross-region command execution bridge.
 *
 * Folia runs each command on the region thread of its source. Commands that
 * touch data owned by another region (e.g. /data, /tag, /trigger on a distant
 * entity or block) must hop to the owning region before touching that data.
 *
 * These helpers execute a task on the target region and block the caller
 * (the source region thread) until it completes, bounded by a timeout so a
 * deadlock between two regions can never hang the server permanently.
 *
 * The caller must pass only immutable/thread-safe values into the task;
 * Minecraft-internal objects of the target region must be captured inside
 * the lambda (e.g. via the scheduled entity handed to the callback).
 */
public final class RegionCommandExecutor {

    @FunctionalInterface
    public interface BlockTask<T> {
        T run() throws CommandSyntaxException;
    }

    @FunctionalInterface
    public interface EntityTask<T> {
        T run(Entity entity) throws CommandSyntaxException;
    }

    private static final SimpleCommandExceptionType ERROR_TIMEOUT = new SimpleCommandExceptionType(
        Component.literal("AzureBranches: cross-region command timed out")
    );
    private static final long TIMEOUT_MILLIS = 2000L;

    private RegionCommandExecutor() {}

    /** Execute on the region owning the given block position. */
    public static <T> T onBlock(final ServerLevel level, final BlockPos pos, final BlockTask<T> task) throws CommandSyntaxException {
        return onBlockRegion(level, pos.getX() >> 4, pos.getZ() >> 4, task);
    }

    /** Execute on the region owning the given chunk coordinates. */
    public static <T> T onBlockRegion(final ServerLevel level, final int cx, final int cz, final BlockTask<T> task) throws CommandSyntaxException {
        final CompletableFuture<T> future = new CompletableFuture<>();
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue
            .queueOrExecuteTickTask(level, cx, cz, () -> complete(future, task));
        return await(future);
    }

    /**
     * Execute on the region owning the given entity. The task receives the
     * scheduled entity reference, which is valid on the target thread.
     */
    public static <T> T onEntity(final Entity entity, final EntityTask<T> task) throws CommandSyntaxException {
        final CompletableFuture<T> future = new CompletableFuture<>();
        entity.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity scheduled) -> {
            try {
                future.complete(task.run(scheduled));
            } catch (final Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return await(future);
    }

    private static <T> void complete(final CompletableFuture<T> future, final BlockTask<T> task) {
        try {
            future.complete(task.run());
        } catch (final Throwable t) {
            future.completeExceptionally(t);
        }
    }

    private static <T> T await(final CompletableFuture<T> future) throws CommandSyntaxException {
        try {
            return future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            throw ERROR_TIMEOUT.create();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ERROR_TIMEOUT.create();
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof CommandSyntaxException cse) {
                throw cse;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }
}
