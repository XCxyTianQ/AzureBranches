/*
 * AzureBranches - EXP Chain Support (v2)
 *
 * Infrastructure for command_blocks.mode=EXP: suspendable / resumable command
 * block chains with true cross-region result semantics.
 *
 * v2 changes from v1:
 *   - IN_FLIGHT / tryBeginChain / endChain removed (replaced by ChainHead).
 *   - registerRemote is retained for same-region fast-path commands.
 *   - DeferredContext collects both completed futures AND pending remote
 *     work items grouped by region, so the Walker can batch-dispatch.
 *
 * Design:
 *   See ChainHead.java (traversal lifecycle), Continuation.java (chain
 *   snapshots), and the 0012 patch (Walker implementation).
 *
 * Threading contract:
 *   - openContext/closeContext/registerRemote/registerDeferred: home region
 *     thread only (thread-local; called during synchronous command execution).
 *   - Futures may be completed from any region thread.
 *   - ChainHead.startTraversal/endWalking/endTraversal: home region thread.
 *
 * Zero Minecraft-internal imports by design.
 */
package com.azurebranches.command;

import com.azurebranches.config.AzureBranchesConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public final class ExpChainSupport {

    // ================================================================
    //  DeferredContext – per-command-block execution scope
    // ================================================================

    /**
     * A remote work item that must be dispatched to a target region.
     * Collected during command execution, dispatched in batch by the Walker.
     */
    public static final class DeferredEntry {
        /** Target region chunk X (>> 4). */
        public final int targetCx;
        /** Target region chunk Z (>> 4). */
        public final int targetCz;
        /** The actual work to run on the target region thread. */
        public final Runnable remoteTask;
        /** Future completed by the remote task. */
        public final CompletableFuture<Boolean> resultFuture;

        public DeferredEntry(final int targetCx, final int targetCz,
                             final Runnable remoteTask,
                             final CompletableFuture<Boolean> resultFuture) {
            this.targetCx = targetCx;
            this.targetCz = targetCz;
            this.remoteTask = remoteTask;
            this.resultFuture = resultFuture;
        }
    }

    /**
     * Result of closing an EXP execution context: all futures (completed
     * and pending) for aggregation, plus deferred entries grouped by
     * target region for batch dispatch.
     */
    public static final class DeferredContext {
        /** All futures registered during this command execution (local + remote). */
        public final List<CompletableFuture<Boolean>> futures;

        /** Cross-region deferred works, grouped by (cx, cz) region key. */
        public final Map<Long, List<DeferredEntry>> deferredByRegion;

        public DeferredContext(final List<CompletableFuture<Boolean>> futures,
                               final Map<Long, List<DeferredEntry>> deferredByRegion) {
            this.futures = Collections.unmodifiableList(futures);
            this.deferredByRegion = Collections.unmodifiableMap(deferredByRegion);
        }

        public boolean hasPendingRemote() {
            if (deferredByRegion.isEmpty()) return false;
            for (final List<DeferredEntry> entries : deferredByRegion.values()) {
                for (final DeferredEntry e : entries) {
                    if (!e.resultFuture.isDone()) return true;
                }
            }
            return false;
        }

        /** Pack region (cx, cz) into a single long key. */
        public static long regionKey(final int cx, final int cz) {
            return ((long) cx << 32) | (cz & 0xFFFF_FFFFL);
        }
    }

    // ================================================================
    //  Thread-local context (per command-block execution, EXP only)
    // ================================================================

    private static final ThreadLocal<List<CompletableFuture<Boolean>>> PENDING_FUTURES = new ThreadLocal<>();
    private static final ThreadLocal<Map<Long, List<DeferredEntry>>> PENDING_DEFERRED = new ThreadLocal<>();
    private static final ThreadLocal<PhaseSnapshot> PHASE_SNAPSHOT = new ThreadLocal<>();

    /**
     * Open a collection scope before dispatching a command block command (EXP only).
     * Backward-compatible: does not create a PhaseSnapshot.
     */
    public static void openContext() {
        PENDING_FUTURES.set(new ArrayList<>(4));
        PENDING_DEFERRED.set(new HashMap<>(2));
    }

    /**
     * Open a collection scope with a PhaseSnapshot for Phase-Based consistency.
     * Commands executed within this scope can read/write block states through
     * the snapshot, ensuring same-Phase read-after-write consistency.
     *
     * @param phaseSnapshot the snapshot for the current Walking Phase (non-null)
     */
    public static void openContext(final PhaseSnapshot phaseSnapshot) {
        PENDING_FUTURES.set(new ArrayList<>(4));
        PENDING_DEFERRED.set(new HashMap<>(2));
        PHASE_SNAPSHOT.set(phaseSnapshot);
    }

    /**
     * Get the PhaseSnapshot for the current Walking Phase, or null if Phase-Based
     * consistency is not active.
     */
    public static PhaseSnapshot getPhaseSnapshot() {
        return PHASE_SNAPSHOT.get();
    }

    /**
     * Clear the PhaseSnapshot from the current thread. Must be called at the
     * end of each Walking Phase (before dispatchAndSuspend or endTraversal).
     */
    public static void clearPhaseSnapshot() {
        PHASE_SNAPSHOT.remove();
    }

    /**
     * Called by awaitable-patched commands when they need to register a
     * CompletableFuture for result aggregation. Returns null when no EXP
     * chain context is active.
     *
     * <p>Prefer {@link #registerDeferred} for new command patches; this
     * method is kept for commands that self-dispatch and only need the
     * future collected (legacy compatibility during transition).</p>
     */
    public static CompletableFuture<Boolean> registerRemote() {
        final List<CompletableFuture<Boolean>> list = PENDING_FUTURES.get();
        if (list == null) {
            return null;
        }
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        list.add(future);
        return future;
    }

    /**
     * Register a remote work item that may need deferred dispatch.
     *
     * @param sameRegion  true when the target position is in the same Folia region
     * @param targetCx    target region chunk X (ignored if sameRegion)
     * @param targetCz    target region chunk Z (ignored if sameRegion)
     * @param remoteTask  the work to execute on the target region thread
     * @return a CompletableFuture, or null when no EXP context is active
     */
    public static CompletableFuture<Boolean> registerDeferred(
        final boolean sameRegion, final int targetCx, final int targetCz,
        final Runnable remoteTask
    ) {
        final List<CompletableFuture<Boolean>> futures = PENDING_FUTURES.get();
        if (futures == null) {
            return null;
        }
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        futures.add(future);

        if (sameRegion) {
            // Same region: execute immediately on this (home) thread
            try {
                remoteTask.run();
                future.complete(Boolean.TRUE);
            } catch (final Exception e) {
                future.completeExceptionally(e);
            }
        } else {
            // Cross region: store as deferred entry for batch dispatch
            final Map<Long, List<DeferredEntry>> deferred = PENDING_DEFERRED.get();
            if (deferred != null) {
                final long key = DeferredContext.regionKey(targetCx, targetCz);
                deferred.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new DeferredEntry(targetCx, targetCz, remoteTask, future));
            }
        }
        return future;
    }

    /** Close the scope and return the collected context for the Walker. */
    public static DeferredContext closeContext() {
        final List<CompletableFuture<Boolean>> futures = PENDING_FUTURES.get();
        PENDING_FUTURES.remove();
        final Map<Long, List<DeferredEntry>> deferred = PENDING_DEFERRED.get();
        PENDING_DEFERRED.remove();
        // Note: PhaseSnapshot is NOT cleared here — it persists across commands
        // within the same Walking Phase. Use clearPhaseSnapshot() at Phase boundary.
        return new DeferredContext(
            futures != null ? futures : List.of(),
            deferred != null ? deferred : Map.of()
        );
    }

    /** True when an EXP execution context is currently active. */
    public static boolean isContextActive() {
        return PENDING_FUTURES.get() != null;
    }

    /**
     * Register a cross-region block state read for deferred batch pre-fetch.
     * Used at Phase start to validate pending write positions from the previous
     * Phase. The readTask receives a CompletableFuture that it should complete
     * with the read result (Boolean.TRUE = success / expected state found).
     *
     * @param targetCx target region chunk X
     * @param targetCz target region chunk Z
     * @param readTask the read operation to execute on the target region thread
     * @return a CompletableFuture, or null when no EXP context is active
     */
    public static CompletableFuture<Boolean> registerDeferredRead(
        final int targetCx, final int targetCz,
        final java.util.function.Consumer<CompletableFuture<Boolean>> readTask
    ) {
        final List<CompletableFuture<Boolean>> futures = PENDING_FUTURES.get();
        if (futures == null) {
            return null;
        }
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        futures.add(future);
        final Map<Long, List<DeferredEntry>> deferred = PENDING_DEFERRED.get();
        if (deferred != null) {
            final long key = DeferredContext.regionKey(targetCx, targetCz);
            deferred.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new DeferredEntry(targetCx, targetCz,
                    () -> readTask.accept(future), future));
        }
        return future;
    }

    // ================================================================
    //  Success-count aggregation (configurable, unchanged from v1)
    // ================================================================

    public enum SuccessCountMode {
        SUM, ALL, ANY
    }

    private static volatile String cachedModeRaw;
    private static volatile SuccessCountMode cachedSuccessMode = SuccessCountMode.SUM;

    public static SuccessCountMode successCountMode() {
        final String raw;
        try {
            raw = AzureBranchesConfig.get().expSuccessCountMode();
        } catch (final IllegalStateException e) {
            return SuccessCountMode.SUM;
        }
        final String cached = cachedModeRaw;
        if (cached != null && cached.equals(raw)) {
            return cachedSuccessMode;
        }
        SuccessCountMode parsed;
        try {
            parsed = SuccessCountMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            System.err.println(
                "[AzureBranches] command_blocks.exp.success_count_mode: unknown value '"
                + raw + "', falling back to SUM");
            parsed = SuccessCountMode.SUM;
        }
        cachedModeRaw = raw;
        cachedSuccessMode = parsed;
        return parsed;
    }

    public static int aggregate(final List<Boolean> results) {
        if (results.isEmpty()) return 0;
        int ok = 0;
        for (final Boolean b : results) {
            if (Boolean.TRUE.equals(b)) ok++;
        }
        return switch (successCountMode()) {
            case SUM -> ok;
            case ALL -> ok == results.size() ? ok : 0;
            case ANY -> ok > 0 ? 1 : 0;
        };
    }

    // ================================================================
    //  Config / stats
    // ================================================================

    public static long remoteTimeoutMs() {
        try {
            return AzureBranchesConfig.get().expRemoteTimeoutMs();
        } catch (final IllegalStateException e) {
            return 1000L;
        }
    }

    /** Maximum deferred entries per region per dispatch batch. */
    public static int maxBatchSize() {
        try {
            return AzureBranchesConfig.get().expBatchMaxSize();
        } catch (final IllegalStateException e) {
            return 15;
        }
    }

    private static final AtomicLong suspended = new AtomicLong();
    private static final AtomicLong resumed = new AtomicLong();
    private static final AtomicLong timeouts = new AtomicLong();
    private static final AtomicLong superseded = new AtomicLong();
    private static final AtomicLong phasePreFetches = new AtomicLong();
    private static final AtomicLong phaseCacheHits = new AtomicLong();

    // EXP3: OCC validation statistics
    private static final AtomicLong validationPassed = new AtomicLong();
    private static final AtomicLong validationRetried = new AtomicLong();
    private static final AtomicLong validationExhausted = new AtomicLong();

    // EXP4: Data pool interception statistics
    private static final AtomicLong dataInterceptBlockWrites = new AtomicLong();
    private static final AtomicLong dataInterceptBlockReads = new AtomicLong();

    public static void onSuspend() { suspended.incrementAndGet(); }
    public static void onResume() { resumed.incrementAndGet(); }
    public static void onSupersede() { superseded.incrementAndGet(); }

    /** EXP3: Record successful Phase validation. */
    public static void onValidationPassed() { validationPassed.incrementAndGet(); }

    /** EXP3: Record Phase retry due to read-set conflict. */
    public static void onValidationRetry() { validationRetried.incrementAndGet(); }

    /** EXP3: Record Phase retry exhaustion. */
    public static void onValidationExhausted() { validationExhausted.incrementAndGet(); }

    /** EXP4: Record a block write intercepted at the data pool level. */
    public static void onDataInterceptBlockWrite() { dataInterceptBlockWrites.incrementAndGet(); }

    /** EXP4: Record a block read intercepted at the data pool level. */
    public static void onDataInterceptBlockRead() { dataInterceptBlockReads.incrementAndGet(); }

    public static void onTimeout(final String command) {
        final long n = timeouts.incrementAndGet();
        if (n == 1L || n % 50L == 0L) {
            System.err.println(
                "[AzureBranches] EXP chain remote result timeout (total " + n + "): '"
                + command + "'");
        }
    }

    /** Record a Phase pre-fetch operation (used for metrics). */
    public static void onPreFetch() { phasePreFetches.incrementAndGet(); }

    /** Record a PhaseSnapshot cache hit (used for metrics). */
    public static void onCacheHit() { phaseCacheHits.incrementAndGet(); }

    public static long suspendedCount() { return suspended.get(); }
    public static long resumedCount() { return resumed.get(); }
    public static long timeoutCount() { return timeouts.get(); }
    public static long supersededCount() { return superseded.get(); }
    public static long preFetchCount() { return phasePreFetches.get(); }
    public static long cacheHitCount() { return phaseCacheHits.get(); }

    /** EXP3: Number of Phases that passed validation. */
    public static long validationPassedCount() { return validationPassed.get(); }
    /** EXP3: Number of Phases retried due to conflict. */
    public static long validationRetryCount() { return validationRetried.get(); }
    /** EXP3: Number of Phases where retries were exhausted. */
    public static long validationExhaustedCount() { return validationExhausted.get(); }
    /** EXP3: Conflict ratio (retries / total validations). */
    public static double validationConflictRatio() {
        final long total = validationPassed.get() + validationRetried.get();
        return total > 0 ? (double) validationRetried.get() / total : 0.0;
    }

    /** EXP4: Data pool block write interception count. */
    public static long dataInterceptBlockWriteCount() { return dataInterceptBlockWrites.get(); }

    /** EXP4: Data pool block read interception count. */
    public static long dataInterceptBlockReadCount() { return dataInterceptBlockReads.get(); }

    // ================================================================
    //  EXP3: Isolation Level
    // ================================================================

    /**
     * ANSI SQL-aligned isolation level provided by the EXP chain system.
     *
     * <p>EXP3 provides <b>Snapshot Isolation</b> as defined by Berenson et al.
     * (1995, "A Critique of ANSI SQL Isolation Levels"):</p>
     * <ul>
     *   <li>No Dirty Reads — own writes visible via PhaseSnapshot cache</li>
     *   <li>No Non-Repeatable Reads — readSet validation detects external writes</li>
     *   <li>No Lost Updates — traversal supersede resolves write-write conflicts</li>
     * </ul>
     *
     * <p>Non-guarantees (by design):</p>
     * <ul>
     *   <li>Phantom Reads — entity selectors may vary between Phases</li>
     *   <li>Write Skew — concurrent chains reading overlapping data may
     *       produce logically inconsistent states</li>
     * </ul>
     */
    public enum IsolationLevel {
        /** Snapshot isolation (EXP3 enabled). */
        SNAPSHOT,
        /** Read committed with write-through cache (EXP2_PB, EXP3 disabled). */
        READ_COMMITTED
    }

    /**
     * Get the current isolation level based on config.
     */
    public static IsolationLevel isolationLevel() {
        try {
            return AzureBranchesConfig.get().expValidationEnabled()
                ? IsolationLevel.SNAPSHOT : IsolationLevel.READ_COMMITTED;
        } catch (final IllegalStateException e) {
            return IsolationLevel.READ_COMMITTED;
        }
    }

    private ExpChainSupport() {}
}
