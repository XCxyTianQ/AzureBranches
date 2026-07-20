/*
 * AzureBranches - Phase-Based Consistency Snapshot
 *
 * PhaseSnapshot provides a per-Walking-phase block state cache that enables
 * consistent read-after-write semantics within a single uninterrupted chain
 * execution window (a "Phase" in EXP v2 terminology).
 *
 * Design rationale:
 *
 *   In the existing EXP v2, DeferredContext only tracks WRITES (what was
 *   dispatched to remote regions). Commands that READ block states do so
 *   from the live Minecraft world, which means:
 *
 *     a) A write in command N is invisible to a read in command N+2 within
 *        the same Phase, because the write hasn't been dispatched yet.
 *     b) After a Phase transition + resume, previous-phase writes might or
 *        might not have completed—reads are non-deterministic.
 *
 *   PhaseSnapshot fixes both by acting as a write-through cache:
 *
 *     • putBlock(pos, state):  cached immediately, available to subsequent
 *       reads in the same Phase (solves problem a).
 *     • markPendingWrite(pos): tracked position, propagated to Continuation,
 *       pre-fetched at the start of the next Phase (solves problem b).
 *
 * Lifecycle:
 *
 *   Phase N start:   new PhaseSnapshot(currentTick)          — clean cache
 *   Phase N Walking: getCached / putBlock / markPendingWrite — write-through
 *   Phase N end:     getPendingWritePositions() → Continuation.pendingWritePositions
 *
 *   Phase N+1 start: PhaseSnapshot.fromContinuation(cont, currentTick)
 *                    → inherits pending positions
 *                    → pre-fetch: queue remote reads for all pending positions
 *                    → Walking resumes with validated cache
 *
 * Thread safety:
 *   PhaseSnapshot is strictly thread-confined to the home region thread during
 *   Walking. No synchronization is needed. The only cross-thread path is the
 *   pendingWritePositions array (written in Phase N, read in Phase N+1 start),
 *   which is safe because the array is fully populated before the Continuation
 *   is created (happens-before via CompletableFuture chain).
 *
 * Zero Minecraft-internal imports by design — all block state references are
 * stored as java.lang.Object.
 */
package com.azurebranches.command;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class PhaseSnapshot {

    // ================================================================
    //  Cache layers
    // ================================================================

    /**
     * Block state cache: BlockPos.asLong() → BlockState.
     * Populated lazily on first read and eagerly on every write.
     * <p>
     * Type note: values are net.minecraft.world.level.block.state.BlockState
     * but stored as Object to avoid Minecraft imports in the common module.
     */
    private final Map<Long, Object> blockCache;

    /**
     * Positions that have been written (via registerDeferred or same-region
     * fast path) during this Phase but whose remote completion has not been
     * confirmed. Propagated to Continuation for the next Phase to pre-fetch.
     */
    private final Set<Long> pendingWritePositions;

    /** Game time (ServerLevel#getGameTime) when this snapshot was created. */
    private final long snapshotTick;

    // ================================================================
    //  Construction
    // ================================================================

    /**
     * Create a fresh snapshot for a new Phase.
     *
     * @param tick current ServerLevel#getGameTime at Phase start
     */
    public PhaseSnapshot(final long tick) {
        this.blockCache = new HashMap<>();
        this.pendingWritePositions = new HashSet<>();
        this.snapshotTick = tick;
    }

    /**
     * Create a snapshot that inherits pending write positions from a
     * previous Phase's Continuation. Caller is responsible for pre-fetching
     * these positions before the Walking phase begins.
     *
     * @param cont the Continuation from the previous Phase
     * @param tick current ServerLevel#getGameTime at Phase start
     * @return a new PhaseSnapshot with inherited pendingWritePositions
     */
    public static PhaseSnapshot fromContinuation(final Continuation cont, final long tick) {
        final PhaseSnapshot snap = new PhaseSnapshot(tick);
        if (cont != null && cont.pendingWritePositions != null) {
            for (final long pos : cont.pendingWritePositions) {
                snap.pendingWritePositions.add(pos);
            }
        }
        return snap;
    }

    // ================================================================
    //  Read path
    // ================================================================

    /**
     * Try to retrieve a cached block state.
     *
     * @param pos BlockPos.asLong()
     * @return the cached BlockState, or null if not cached
     */
    public Object getCached(final long pos) {
        return blockCache.get(pos);
    }

    /**
     * Check whether a position is in the cache (regardless of value, including null).
     */
    public boolean isCached(final long pos) {
        return blockCache.containsKey(pos);
    }

    // ================================================================
    //  Write path
    // ================================================================

    /**
     * Store a block state in the cache. Called for:
     * <ul>
     *   <li>Read-through: after reading from the live world, cache the result</li>
     *   <li>Write-through: after a deferred write is registered, cache the expected result</li>
     * </ul>
     *
     * @param pos   BlockPos.asLong()
     * @param state the block state (null = air/removed)
     */
    public void putBlock(final long pos, final Object state) {
        blockCache.put(pos, state);
    }

    /**
     * Mark a position as having a pending cross-region write.
     * This position will be pre-fetched (or the cached value carried forward)
     * at the start of the next Phase.
     *
     * @param pos BlockPos.asLong()
     */
    public void markPendingWrite(final long pos) {
        pendingWritePositions.add(pos);
    }

    /**
     * Check if a position has a pending write from this or a previous Phase.
     * When true, the caller may choose to use the cached value rather than
     * initiating a new remote read.
     *
     * @param pos BlockPos.asLong()
     */
    public boolean hasPendingWrite(final long pos) {
        return pendingWritePositions.contains(pos);
    }

    // ================================================================
    //  Serialization (for Continuation propagation)
    // ================================================================

    /**
     * Extract the set of pending write positions for propagation to the next
     * Phase via Continuation.pendingWritePositions.
     *
     * @return a defensive copy as a primitive array
     */
    public long[] getPendingWritePositions() {
        final long[] arr = new long[pendingWritePositions.size()];
        int i = 0;
        for (final long pos : pendingWritePositions) {
            arr[i++] = pos;
        }
        return arr;
    }

    // ================================================================
    //  Introspection
    // ================================================================

    /** Game time when this snapshot was created. */
    public long getSnapshotTick() {
        return snapshotTick;
    }

    /** Number of cached block state entries. */
    public int cacheSize() {
        return blockCache.size();
    }

    /** Number of pending cross-region write positions. */
    public int pendingWriteCount() {
        return pendingWritePositions.size();
    }
}
