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

    /**
     * EXP3: Block positions (BlockPos.asLong) that were read during the Phase
     * that created this Continuation. Used by PhaseValidator at Phase resume
     * to detect external modifications (OCC read-set validation).
     *
     * <p>Null or empty when the dispatching Phase performed no cross-region
     * reads, or when EXP3 validation is disabled.</p>
     */
    public volatile long[] readSetPositions;

    /** EXP3: Retry count for this Continuation's Phase (0 = first attempt). */
    public volatile int retryCount;

    /**
     * EXP4: Score keys ("objective:holder") with pending writes from the
     * Phase that created this Continuation. Propagated for cross-Phase
     * score consistency via ScoreLayer inverse-operation compensation.
     */
    public volatile String[] pendingScoreKeys;

    /**
     * EXP4: Score keys read during the Phase that created this Continuation.
     * Propagated for OCC score read-set validation.
     */
    public volatile String[] readSetScoreKeys;

    /**
     * EXP4: Pre-write old block states captured from the dispatching batch's
     * remote executions (BlockPos.asLong() → BlockState). Merged into the next
     * Phase's oldBlockStates by PhaseSnapshot.fromContinuation, so an OCC
     * conflict can restore the world to its pre-Phase state before retrying.
     *
     * <p>Null when the dispatching Phase performed no cross-region writes or
     * when capture was not wired.</p>
     */
    public volatile java.util.Map<Long, Object> oldStateCapture;

    /**
     * EXP4: BlockPos.asLong() where the Phase that created this Continuation
     * began walking. Used to replay the whole Phase from its start on retry.
     */
    public volatile long phaseStartPos;

    /**
     * EXP4: Direction.get3DDataValue() at Phase start. Used with phaseStartPos
     * for Phase replay on retry.
     */
    public volatile int phaseStartDir;

    /**
     * EXP6Plus: Entity read-set carry (entityId -> scoreboardName) from the
     * Phase that created this Continuation. Inherited by the next Phase's
     * snapshot so CHECK_ENTITY_READ_SET still sees entities resolved by an
     * earlier Phase of the same chain (an entity that dies mid-chain must
     * conflict on the next verify).
     */
    public volatile java.util.Map<Integer, String> entityReadCarry;

    /**
     * EXP6Plus: Score write carry ("objective:holder" -> pre-write value)
     * from the Phase that created this Continuation. Inherited so a later
     * Phase's rollback compensates score writes of earlier Phases (chain =
     * one transaction across Phases).
     */
    public volatile java.util.Map<String, Integer> oldScoreValuesCarry;

    /**
     * EXP6Plus: Score cache carry ("objective:holder" -> value after the
     * dispatching Phase's writes). Inherited for inverse-operation
     * compensation (netDelta = cachedNew - oldValue).
     */
    public volatile java.util.Map<String, Integer> scoreCacheCarry;

    Continuation(final long traversalId, final long cursorPos, final int direction3d,
                 final int remaining, final int stepCount) {
        this.traversalId = traversalId;
        this.cursorPos = cursorPos;
        this.direction3d = direction3d;
        this.remaining = remaining;
        this.stepCount = stepCount;
    }
}
