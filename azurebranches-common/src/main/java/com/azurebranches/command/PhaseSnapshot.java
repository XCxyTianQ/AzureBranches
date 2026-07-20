/*
 * AzureBranches - Phase-Based Consistency Snapshot (EXP3)
 *
 * PhaseSnapshot provides a per-Walking-phase block state cache that enables
 * consistent read-after-write semantics within a single uninterrupted chain
 * execution window (a "Phase" in EXP terminology).
 *
 * EXP3 extensions (v26.1.2-EXP3):
 *
 *   Read Set Validation (OCC):
 *     recordRead(pos, tick) records every cross-region read. On Phase commit,
 *     the readSet is checked against external modifications. Conflict → rollback.
 *
 *   Write Set Rollback:
 *     oldBlockStates stores pre-write values for every putBlock(). On conflict,
 *     Phase rolls back by restoring oldBlockStates.
 *
 *   Savepoints:
 *     createSavepoint() / rollbackTo() enable partial rollback within a Phase,
 *     so that errors late in the Phase do not invalidate all earlier work.
 *
 *   Irreversible Operations:
 *     Operations like /say, /tellraw are marked via markIrreversible(). They
 *     execute regardless of Phase outcome (Nested Top Action semantics, per
 *     ARIES recovery algorithm).
 *
 * Lifecycle (EXP3):
 *
 *   Phase N start:   new PhaseSnapshot(currentTick)          — clean cache + readSet + savepoints
 *   Phase N Walking: getCached / putBlock / markPendingWrite / recordRead / createSavepoint
 *   Phase N end:     getPendingWritePositions() + getReadSet() → Continuation
 *
 *   Phase N+1 start: PhaseSnapshot.fromContinuation(cont, currentTick)
 *                    → validates readSet against external changes
 *                    → conflict? rollback + retry. no conflict? commit + resume.
 *
 * Thread safety:
 *   PhaseSnapshot is strictly thread-confined to the home region thread during
 *   Walking. No synchronization is needed.
 *
 * Zero Minecraft-internal imports by design — all block state references are
 * stored as java.lang.Object.
 */
package com.azurebranches.command;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class PhaseSnapshot {

    // ================================================================
    //  Cache layers
    // ================================================================

    /** Block state cache: BlockPos.asLong() → BlockState. */
    private final Map<Long, Object> blockCache;

    /** Positions with pending cross-region writes (propagated to Continuation). */
    private final Set<Long> pendingWritePositions;

    /**
     * EXP3: Positions read during this Phase, with the tick at which they
     * were read. Used for OCC validation at Phase commit time.
     * <p>
     * Value is the game tick when the read occurred (ServerLevel#getGameTime).
     */
    private final Map<Long, Long> readSet;

    /**
     * EXP3: Pre-write block states for rollback. Before any putBlock(),
     * the old state is recorded here. If the Phase is rolled back, these
     * values are restored.
     */
    private final Map<Long, Object> oldBlockStates;

    /**
     * EXP3: Savepoint stack for intra-Phase partial rollback.
     * Deque (stack semantics) — most recent savepoint is on top.
     */
    private final Deque<Savepoint> savepoints;

    /**
     * EXP3: Whether this Phase contains irreversible side-effects
     * (e.g. /say, /tellraw). Such Phases cannot be fully rolled back;
     * only data operations are compensated.
     */
    private boolean hasIrreversibleOps;

    /** Game time (ServerLevel#getGameTime) when this snapshot was created. */
    private final long snapshotTick;

    // ================================================================
    //  Construction
    // ================================================================

    public PhaseSnapshot(final long tick) {
        this.blockCache = new HashMap<>();
        this.pendingWritePositions = new HashSet<>();
        this.readSet = new HashMap<>();
        this.oldBlockStates = new HashMap<>();
        this.savepoints = new ArrayDeque<>();
        this.snapshotTick = tick;
    }

    public static PhaseSnapshot fromContinuation(final Continuation cont, final long tick) {
        final PhaseSnapshot snap = new PhaseSnapshot(tick);
        if (cont != null) {
            if (cont.pendingWritePositions != null) {
                for (final long pos : cont.pendingWritePositions) {
                    snap.pendingWritePositions.add(pos);
                }
            }
            // EXP3: inherit readSet positions for validation context
            if (cont.readSetPositions != null) {
                for (final long pos : cont.readSetPositions) {
                    snap.readSet.put(pos, tick);
                }
            }
        }
        return snap;
    }

    // ================================================================
    //  Read path
    // ================================================================

    public Object getCached(final long pos) {
        return blockCache.get(pos);
    }

    public boolean isCached(final long pos) {
        return blockCache.containsKey(pos);
    }

    /**
     * EXP3: Record a cross-region read for OCC validation.
     * Called by commands that read block states during EXP chain execution.
     *
     * @param pos  BlockPos.asLong()
     * @param tick current ServerLevel#getGameTime at read time
     */
    public void recordRead(final long pos, final long tick) {
        readSet.putIfAbsent(pos, tick);
    }

    /**
     * EXP3: Get the read set for validation and Continuation propagation.
     */
    public Map<Long, Long> getReadSet() {
        return readSet;
    }

    /** EXP3: Number of recorded cross-region reads in this Phase. */
    public int readSetSize() {
        return readSet.size();
    }

    // ================================================================
    //  Write path (with rollback support)
    // ================================================================

    /**
     * Store a block state in the cache.
     * EXP3: if an old state is provided, it is recorded for potential rollback.
     *
     * @param pos      BlockPos.asLong()
     * @param state    the new block state (null = air/removed)
     */
    public void putBlock(final long pos, final Object state) {
        blockCache.put(pos, state);
    }

    /**
     * EXP3: Store with old-state recording for rollback.
     * Call this BEFORE the write executes, so the old state is captured.
     *
     * @param pos      BlockPos.asLong()
     * @param newState the expected block state after write
     * @param oldState the block state before the write (for rollback)
     */
    public void putBlock(final long pos, final Object newState, final Object oldState) {
        blockCache.put(pos, newState);
        if (oldState != null && !oldBlockStates.containsKey(pos)) {
            oldBlockStates.put(pos, oldState); // only record first old state
        }
    }

    public void markPendingWrite(final long pos) {
        pendingWritePositions.add(pos);
    }

    public boolean hasPendingWrite(final long pos) {
        return pendingWritePositions.contains(pos);
    }

    /**
     * EXP3: Get old block states for rollback restoration.
     */
    public Map<Long, Object> getOldBlockStates() {
        return oldBlockStates;
    }

    // ================================================================
    //  EXP3: Savepoints (intra-Phase partial rollback)
    // ================================================================

    /**
     * Create a savepoint at the current Phase state.
     * Later, {@link #rollbackTo(Savepoint)} can restore to this point,
     * discarding all cache/write/read changes made after the savepoint.
     *
     * @param cursorPos  current chain walker cursor (BlockPos.asLong())
     * @param direction3d current chain direction (Direction.get3DDataValue())
     * @return a Savepoint handle
     */
    public Savepoint createSavepoint(final long cursorPos, final int direction3d) {
        final Savepoint sp = new Savepoint(
            cursorPos, direction3d,
            new HashMap<>(blockCache),
            new HashSet<>(pendingWritePositions),
            savepoints.size()
        );
        savepoints.push(sp);
        return sp;
    }

    /**
     * Rollback to a previously created savepoint. All block cache entries,
     * pending write markers, and savepoints created AFTER the target are
     * discarded.
     *
     * @param sp the savepoint to restore (must have been created in this Phase)
     * @return true if rollback succeeded, false if savepoint not found
     */
    public boolean rollbackTo(final Savepoint sp) {
        // Validate the savepoint belongs to this Phase
        boolean found = false;
        for (final Savepoint s : savepoints) {
            if (s == sp) { found = true; break; }
        }
        if (!found) return false;

        // Pop savepoints until we reach the target
        while (!savepoints.isEmpty() && savepoints.peek() != sp) {
            savepoints.pop();
        }
        if (!savepoints.isEmpty()) {
            savepoints.pop(); // remove the target savepoint itself
        }

        // Restore cache state to savepoint snapshot
        blockCache.clear();
        blockCache.putAll(sp.blockCacheSnapshot);

        // Restore pending writes
        pendingWritePositions.clear();
        pendingWritePositions.addAll(sp.pendingWritesSnapshot);

        // Note: readSet and oldBlockStates are NOT rolled back because
        // they represent "what actually happened" — rollback doesn't
        // undo the fact that reads occurred.

        return true;
    }

    /** Get the current savepoint depth. */
    public int savepointDepth() {
        return savepoints.size();
    }

    // ================================================================
    //  EXP3: Irreversible operations
    // ================================================================

    /**
     * Mark that this Phase contains an irreversible side-effect.
     * Affects rollback strategy: data operations are compensated, but
     * the irreversible operation (e.g. chat message) is left as-is.
     */
    public void markIrreversible() {
        this.hasIrreversibleOps = true;
    }

    /** Whether this Phase has irreversible side-effects. */
    public boolean hasIrreversibleOps() {
        return hasIrreversibleOps;
    }

    // ================================================================
    //  Serialization (for Continuation propagation)
    // ================================================================

    public long[] getPendingWritePositions() {
        final long[] arr = new long[pendingWritePositions.size()];
        int i = 0;
        for (final long pos : pendingWritePositions) {
            arr[i++] = pos;
        }
        return arr;
    }

    /**
     * EXP3: Extract read set positions for Continuation propagation.
     */
    public long[] getReadSetPositions() {
        final long[] arr = new long[readSet.size()];
        int i = 0;
        for (final long pos : readSet.keySet()) {
            arr[i++] = pos;
        }
        return arr;
    }

    // ================================================================
    //  Introspection
    // ================================================================

    public long getSnapshotTick() { return snapshotTick; }
    public int cacheSize() { return blockCache.size(); }
    public int pendingWriteCount() { return pendingWritePositions.size(); }
    public int readSetCount() { return readSet.size(); }
    public int oldBlockStateCount() { return oldBlockStates.size(); }

    // ================================================================
    //  EXP3: Savepoint record
    // ================================================================

    /**
     * A lightweight snapshot of Phase state at a point during Walking.
     * Enables partial rollback when a late-Phase operation fails validation.
     */
    public static final class Savepoint {
        /** Walker cursor position at savepoint time. */
        public final long cursorPos;
        /** Chain direction at savepoint time. */
        public final int direction3d;
        /** Shallow copy of block cache entries. */
        final Map<Long, Object> blockCacheSnapshot;
        /** Copy of pending write position set. */
        final Set<Long> pendingWritesSnapshot;
        /** Savepoint index (0 = first, increases with depth). */
        public final int depth;

        Savepoint(final long cursorPos, final int direction3d,
                  final Map<Long, Object> blockCacheSnapshot,
                  final Set<Long> pendingWritesSnapshot,
                  final int depth) {
            this.cursorPos = cursorPos;
            this.direction3d = direction3d;
            this.blockCacheSnapshot = blockCacheSnapshot;
            this.pendingWritesSnapshot = pendingWritesSnapshot;
            this.depth = depth;
        }
    }
}
