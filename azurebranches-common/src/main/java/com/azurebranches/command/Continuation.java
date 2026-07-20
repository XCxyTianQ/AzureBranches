/*
 * AzureBranches - EXP Chain Continuation
 *
 * A Continuation is a snapshot of a suspended command block chain. It
 * carries the walker's position and the pending remote operations that
 * must complete before the chain may resume.
 *
 * Lifecycle under ChainHead (see ChainHead.java for the full state machine):
 *
 *   CREATE:     new Continuation(traversalId, cursor, dir, remaining, stepCount)
 *               → registered into ChainHead.pending
 *
 *   WAITING:    remote operations in-flight (one or more CompletableFuture)
 *
 *   SUPERSEDED: new traversal started while this one waited
 *               → superseded=true; resume callback will discard
 *
 *   COMPLETED:  all futures done, not superseded
 *               → resume callback fires on home region thread
 *               → chain walker continues from (cursor, direction, remaining)
 *
 * Thread safety:
 *   - traversalId: written once at creation
 *   - superseded: AtomicBoolean, read by resume callback, written by ChainHead.startTraversal
 *   - cursor/direction/remaining: read after resume on home region thread (single writer)
 */
package com.azurebranches.command;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Continuation {

    /** Traversal that created this continuation. Resumable only while this equals ChainHead.currentTraversalId. */
    final long traversalId;

    /** BlockPos.asLong() of the command block that was executing when the chain suspended. */
    public final long cursorPos;

    /** Direction.get3DDataValue() of the chain at suspension time. */
    public final int direction3d;

    /** Remaining steps in the chain (MAX_COMMAND_SEQUENCE_LENGTH counter). */
    public final int remaining;

    /** How many command steps are bundled in this continuation's pending remote batch. */
    public final int stepCount;

    /** Set to true by ChainHead when a newer traversal supersedes this continuation. */
    public final AtomicBoolean superseded = new AtomicBoolean(false);

    /**
     * Block positions (BlockPos.asLong) with pending cross-region writes from the
     * Phase that created this Continuation. The next Phase uses these positions for
     * pre-fetch validation and cache warm-up.
     *
     * <p>Null or empty when the dispatching Phase had no cross-region writes,
     * or when Phase-Based snapshot is disabled.</p>
     */
    public volatile long[] pendingWritePositions;

    Continuation(final long traversalId, final long cursorPos, final int direction3d,
                 final int remaining, final int stepCount) {
        this.traversalId = traversalId;
        this.cursorPos = cursorPos;
        this.direction3d = direction3d;
        this.remaining = remaining;
        this.stepCount = stepCount;
    }
}
