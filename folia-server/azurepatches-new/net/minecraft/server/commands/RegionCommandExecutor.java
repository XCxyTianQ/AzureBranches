package net.minecraft.server.commands;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * AzureBranches - cross-region command execution bridge.
 *
 * Folia runs each command on the region thread of its source. Commands that
 * touch data owned by another region (e.g. /data, /tag, /trigger on a distant
 * entity or block) must hop to the owning region before touching that data.
 *
 * EXP4Plus split this bridge into two families:
 *
 *  - <b>Async (preferred)</b>: {@code onBlockAsync}/{@code onEntityAsync} return
 *    a {@link CompletableFuture} and never block. A tick (region) thread should
 *    always use these and resume the command via a callback, because blocking a
 *    tick thread stalls every entity/block/command owned by that region.
 *
 *  - <b>Blocking (legacy)</b>: {@code onBlock}/{@code onEntity} block the caller
 *    up to 2s. These are only safe on a non-tick thread (worker/async/console).
 *    They refuse to block a tick thread (fail fast) so the anti-pattern can never
 *    silently stall a region again.
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
    private static final AtomicBoolean TICK_BLOCK_WARNED = new AtomicBoolean(false);

    /** Bounded daemon pool for offloading synchronous data-access work off tick threads. */
    private static final Executor WORKER_POOL = Executors.newFixedThreadPool(2, r -> {
        final Thread t = new Thread(r, "AzureBranches-Data-Worker");
        t.setDaemon(true);
        return t;
    });

    private RegionCommandExecutor() {}

    /** Worker pool for non-tick-thread blocking data access (used by DataAccessor defaults). */
    public static Executor workerPool() {
        return WORKER_POOL;
    }

    // ----------------------------------------------------------------
    //  Async primitives (the optimal, non-blocking path)
    // ----------------------------------------------------------------

    /** Execute on the region owning the given block position, without blocking. */
    public static <T> CompletableFuture<T> onBlockAsync(final ServerLevel level, final BlockPos pos, final BlockTask<T> task) {
        return onBlockRegionAsync(level, pos.getX() >> 4, pos.getZ() >> 4, task);
    }

    /** Execute on the region owning the given chunk coordinates, without blocking. */
    public static <T> CompletableFuture<T> onBlockRegionAsync(final ServerLevel level, final int cx, final int cz, final BlockTask<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue
            .queueOrExecuteTickTask(level, cx, cz, () -> complete(future, task));
        return future;
    }

    /** Execute on the region owning the given entity, without blocking.
     *
     * <p>AzureBranches EXP6: when the calling thread IS the entity's owning
     * tick thread, the task runs inline. Folia's entity scheduler queues to
     * the entity's next tick even on its own thread (scheduleOrExecute only
     * inlines during the entity's own tick stage), which silently broke the
     * same-region fast path of {@code /data} inside command-block chains —
     * every read/write would land one tick late. Running inline on the
     * owning tick thread is safe by Folia's threading contract.</p> */
    public static <T> CompletableFuture<T> onEntityAsync(final Entity entity, final EntityTask<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        if (TickThread.isTickThreadFor(entity)) {
            try {
                future.complete(task.run(entity));
            } catch (final Throwable t) {
                future.completeExceptionally(t);
            }
        } else {
            entity.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity scheduled) -> {
                try {
                    future.complete(task.run(scheduled));
                } catch (final Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        }
        return future;
    }

    // ----------------------------------------------------------------
    //  Global tick primitives — for server-global data (e.g. scoreboard)
    // ----------------------------------------------------------------

    /**
     * Execute on the Folia global tick thread, without blocking the caller.
     *
     * <p>Server-global state (scoreboard, registries, gamerules...) is only
     * safe to touch on the global tick thread. Commands that mutate such state
     * (e.g. {@code /scoreboard}) dispatch their work through this primitive and
     * route feedback back via {@link #runOnSource}. When the calling thread IS
     * the global tick thread (console commands), the task runs inline and the
     * returned future is already completed — the same-region fast path.</p>
     */
    public static <T> CompletableFuture<T> onGlobalAsync(final BlockTask<T> task) {
        if (io.papermc.paper.threadedregions.RegionizedServer.isGlobalTickThread()) {
            final CompletableFuture<T> future = new CompletableFuture<>();
            complete(future, task);
            return future;
        }
        final CompletableFuture<T> future = new CompletableFuture<>();
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> complete(future, task));
        return future;
    }

    /**
     * Blocking legacy variant of {@link #onGlobalAsync}: waits up to 2s for the
     * global tick thread to run the task. Only safe on a non-tick thread; on a
     * tick thread it warns once (see {@link #await}) but keeps working.
     */
    public static <T> T onGlobal(final BlockTask<T> task) throws CommandSyntaxException {
        return await(onGlobalAsync(task));
    }

    // ----------------------------------------------------------------
    //  Blocking (legacy) variants — fail fast on a tick thread
    // ----------------------------------------------------------------

    public static <T> T onBlock(final ServerLevel level, final BlockPos pos, final BlockTask<T> task) throws CommandSyntaxException {
        return await(onBlockAsync(level, pos, task));
    }

    public static <T> T onBlockRegion(final ServerLevel level, final int cx, final int cz, final BlockTask<T> task) throws CommandSyntaxException {
        return await(onBlockRegionAsync(level, cx, cz, task));
    }

    public static <T> T onEntity(final Entity entity, final EntityTask<T> task) throws CommandSyntaxException {
        return await(onEntityAsync(entity, task));
    }

    // ----------------------------------------------------------------
    //  Thread context helpers
    // ----------------------------------------------------------------

    /** True when the calling thread is a Folia tick (region/global/shutdown) thread. */
    public static boolean isTickThread() {
        return TickThread.isTickThread();
    }

    /**
     * Route a feedback callback (sendSuccess/sendFailure) back onto the source's
     * owning region. Console sources have a null level and run inline.
     */
    public static void runOnSource(final CommandSourceStack source, final Runnable action) {
        final ServerLevel level = source.getLevel();
        if (level == null) {
            action.run();
            return;
        }
        final int cx = Mth.floor(source.getPosition().x) >> 4;
        final int cz = Mth.floor(source.getPosition().z) >> 4;
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue
            .queueOrExecuteTickTask(level, cx, cz, action);
    }

    private static <T> void complete(final CompletableFuture<T> future, final BlockTask<T> task) {
        try {
            future.complete(task.run());
        } catch (final Throwable t) {
            future.completeExceptionally(t);
        }
    }

    private static <T> T await(final CompletableFuture<T> future) throws CommandSyntaxException {
        if (TickThread.isTickThread()) {
            // The optimal path is onBlockAsync/onEntityAsync. Some synchronous
            // commands (notably /data) still block because their Brigadier
            // interface returns a value synchronously; warn once so the
            // anti-pattern is never silent, but keep them working.
            if (TICK_BLOCK_WARNED.compareAndSet(false, true)) {
                System.err.println(
                    "[AzureBranches] WARNING: blocking a tick/region thread on cross-region work. "
                    + "Prefer onBlockAsync/onEntityAsync + callback (see RegionCommandExecutor)."
                );
            }
        }
        try {
            return future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            throw ERROR_TIMEOUT.create();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ERROR_TIMEOUT.create();
        } catch (final ExecutionException e) {
            return unwrap(e);
        }
    }

    /** Re-throw the cause of a failed cross-region task, preserving command errors. */
    public static <T> T unwrap(final ExecutionException e) throws CommandSyntaxException {
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
