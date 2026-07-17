/*
 * AzureBranches - EXP Chain Head Registry
 *
 * Each command block chain head is identified by (level, headBlockPos) and
 * tracked by a ChainHead instance. The head manages:
 *
 *   1. Walking lock    – prevents concurrent traversals on the same chain.
 *                         Held only while the walker is actively iterating
 *                         command blocks on the home region thread (µs-scale).
 *                         Released immediately when the chain suspends for
 *                         remote work → new triggers can start new traversals.
 *
 *   2. Traversal ID     – monotonic counter. Each new trigger produces a
 *                         fresh traversal. Old Continuations whose remote ops
 *                         complete after a newer traversal started are
 *                         discarded (superseded=true).
 *
 *   3. Continuation set – active continuations waiting on remote ops.
 *                         Each carry the walker cursor, direction, and
 *                         remaining step counter for the home region thread
 *                         to resume from.
 *
 * Lifecycle per trigger:
 *
 *   startTraversal()        → walking=true, ++traversalId, supersede old
 *   walk ... walk ... walk  → collect remote work, build continuations
 *   endWalking()            → walking=false (even if continuation pending)
 *
 *   ... time passes, remote ops complete ...
 *
 *   onContinuationReady(c)  → if still current traversal, resume walk
 *   walk ... walk ... walk  → continue from (c.cursorPos, c.direction3d, ...)
 *   endTraversal()          → remove from registry (only when chain reaches end)
 *
 * Thread contract:
 *   - startTraversal/endWalking/endTraversal: home region thread only
 *   - onContinuationReady: home region thread (routed via queueOrExecuteTickTask)
 *   - Continuation.superseded: written by startTraversal (home thread),
 *     read by remote-future callbacks (any thread) – AtomicBoolean
 */
package com.azurebranches.command;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ChainHead {

    // ---- identity ----

    private record ChainKey(Object level, long headPos) {}

    private static final ConcurrentHashMap<ChainKey, ChainHead> REGISTRY = new ConcurrentHashMap<>();

    // ---- instance state ----

    final ChainKey key;

    /** Monotonic ID; each startTraversal bumps it. */
    final AtomicLong nextTraversalId = new AtomicLong(0);

    /** ID of the current (most recent) traversal. */
    volatile long currentTraversalId;

    /** Continuations waiting for remote results. */
    final Set<Continuation> pendingContinuations = ConcurrentHashMap.newKeySet();

    /** True while the walker is iterating command blocks on the home thread. */
    volatile boolean walking;

    /** Estimated average remote latency in ticks; used for MAX_PENDING heuristics. */
    volatile int avgSuspendTicks = 1;

    private ChainHead(final ChainKey key) {
        this.key = key;
    }

    // ---- factory ----

    /**
     * Resolve or create the ChainHead for a (level, headPos) pair.
     * Returns null when the head does not exist and shouldStart is false.
     */
    public static ChainHead resolve(final Object level, final long headPos) {
        final ChainKey key = new ChainKey(level, headPos);
        return REGISTRY.computeIfAbsent(key, ChainHead::new);
    }

    // ---- traversal lifecycle (home region thread) ----

    /**
     * Start a new traversal. Supersedes all pending continuations from any
     * previous traversal.
     *
     * @return the new traversalId, or -1L if the walking lock is held
     */
    public long startTraversal() {
        if (walking) {
            return -1L;
        }
        walking = true;
        final long id = nextTraversalId.incrementAndGet();
        currentTraversalId = id;
        for (final Continuation c : pendingContinuations) {
            c.superseded.set(true);
        }
        return id;
    }

    /** Release the walking lock. Safe to call even after the chain suspended. */
    public void endWalking() {
        walking = false;
    }

    /**
     * Terminate the chain head entirely. Called when the chain completes
     * (reaches end or max steps) and no more continuations are pending.
     */
    public void endTraversal() {
        pendingContinuations.clear();
        REGISTRY.remove(key);
    }

    /** Check whether a traversal is currently active. */
    public boolean isWalking() {
        return walking;
    }

    // ---- continuation management ----

    /** Create a continuation under the current traversal. */
    public Continuation createContinuation(final long cursorPos, final int direction3d,
                                           final int remaining, final int stepCount) {
        final Continuation c = new Continuation(currentTraversalId, cursorPos, direction3d,
            remaining, stepCount);
        pendingContinuations.add(c);
        return c;
    }

    /** Remove a continuation from the pending set (called on resume). */
    public void removeContinuation(final Continuation c) {
        pendingContinuations.remove(c);
    }

    /**
     * Returns true when the given continuation still belongs to the current
     * traversal and has not been superseded.
     */
    public boolean isCurrent(final Continuation c) {
        return c != null && c.traversalId == currentTraversalId && !c.superseded.get();
    }

    // ---- stats / introspection ----

    public long currentTraversalId() { return currentTraversalId; }
    public int pendingCount() { return pendingContinuations.size(); }

    // ---- global maintenance ----

    /** Drop all chain heads belonging to a level that is being unloaded. */
    public static void dropLevel(final Object level) {
        REGISTRY.entrySet().removeIf(e -> {
            if (e.getKey().level() == level) {
                for (final Continuation c : e.getValue().pendingContinuations) {
                    c.superseded.set(true);
                }
                return true;
            }
            return false;
        });
    }

    /** Total number of active chain heads. */
    public static int globalCount() {
        return REGISTRY.size();
    }
}
