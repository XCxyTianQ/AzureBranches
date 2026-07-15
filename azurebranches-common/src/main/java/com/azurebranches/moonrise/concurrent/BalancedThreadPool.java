/*
 * AzureBranches — Balanced Thread Pool (Legacy Adapter)
 *
 * Backward-compatible wrapper around {@link WorkerThreadPool}.
 * Existing code using BalancedThreadPool continues to work;
 * new code should use WorkerThreadPool with WorkerPoolManager directly.
 *
 * ─────────────────────────────────────────────────────────────────────
 * Spottedleaf / concurrentutil (MIT License)
 * Copyright (c) Spottedleaf
 * ─────────────────────────────────────────────────────────────────────
 */
package com.azurebranches.moonrise.concurrent;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * @deprecated Use {@link WorkerThreadPool} via {@link WorkerPoolManager} instead.
 *             This class remains for backward compatibility only.
 */
@Deprecated
public final class BalancedThreadPool {

    private static final int PRIORITY_LEVELS = 6;

    private final String name;
    private final long holdTimeNanos;
    private final Worker[] workers;
    private final AtomicInteger nextWorker = new AtomicInteger(0);
    private volatile boolean running = true;

    /**
     * @deprecated Use {@link WorkerThreadPool.Builder} instead.
     */
    @Deprecated
    public BalancedThreadPool(String name, long holdTimeNanos, int threadCount) {
        this.name = name;
        this.holdTimeNanos = holdTimeNanos;
        this.workers = new Worker[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new Worker(name + "-" + i, holdTimeNanos);
            workers[i].start();
        }
    }

    /**
     * @deprecated Use {@link WorkerThreadPool#createGroup()} instead.
     */
    @Deprecated
    public OrderedGroup createGroup() {
        return new OrderedGroup(this);
    }

    /**
     * @deprecated Use {@link WorkerThreadPool#shutdown(long)} instead.
     */
    @Deprecated
    public void shutdown() {
        running = false;
        for (Worker w : workers) {
            LockSupport.unpark(w);
        }
    }

    /**
     * @deprecated Use {@link WorkerThreadPool#shutdown(long)} instead.
     */
    @Deprecated
    public boolean join(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Worker w : workers) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return false;
            try {
                w.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * @deprecated Use {@link WorkerThreadPool#halt()} instead.
     */
    @Deprecated
    public void halt() {
        running = false;
        for (Worker w : workers) {
            w.interrupt();
        }
    }

    /**
     * @deprecated Use {@link WorkerPoolManager#get(String)} instead.
     */
    @Deprecated
    public void adjustThreadCount(int newCount) {
        // No-op: thread count is fixed after construction
    }

    /**
     * @deprecated Use {@link WorkerThreadPool#threadCount()} instead.
     */
    @Deprecated
    public int threadCount() {
        return workers.length;
    }

    void submit(PrioritisedTask task) {
        if (!running) {
            task.cancel();
            return;
        }
        int idx = nextWorker.getAndUpdate(i -> (i + 1) % workers.length);
        workers[idx].enqueue(task);
    }

    // ── Ordered Group ──

    /**
     * @deprecated Use {@link WorkerThreadPool.OrderedGroup} instead.
     */
    @Deprecated
    public static final class OrderedGroup {
        private final BalancedThreadPool pool;
        private final Map<Long, PendingTask> areaQueues = new ConcurrentHashMap<>();
        private final Queue<PrioritisedTask> pendingHeads = new ConcurrentLinkedQueue<>();

        OrderedGroup(BalancedThreadPool pool) {
            this.pool = pool;
        }

        /**
         * @deprecated Use {@link WorkerThreadPool.OrderedGroup#queue(long, Runnable, int)} instead.
         */
        @Deprecated
        public void queue(long areaKey, Runnable task, int priority) {
            PrioritisedTask pt = new PrioritisedTask(task, priority, areaKey, this);
            PendingTask existing = areaQueues.putIfAbsent(areaKey, new PendingTask(pt));
            if (existing == null) {
                pendingHeads.add(pt);
                pool.submit(pt);
            } else {
                existing.append(pt);
            }
        }

        void onComplete(PrioritisedTask completed) {
            long areaKey = completed.areaKey;
            PendingTask pt = areaQueues.get(areaKey);
            if (pt == null) return;

            PrioritisedTask next = pt.poll();
            if (next == null) {
                areaQueues.remove(areaKey);
            } else {
                pendingHeads.add(next);
                pool.submit(next);
            }
        }
    }

    // ── PrioritisedTask (legacy, identical to WorkerThreadPool's) ──

    /**
     * @deprecated Use {@link WorkerThreadPool.PrioritisedTask} instead.
     */
    @Deprecated
    public static final class PrioritisedTask implements Runnable, Comparable<PrioritisedTask> {
        final Runnable delegate;
        final int priority;
        final long areaKey;
        final long createdAt;
        final BalancedThreadPool.OrderedGroup owner;
        volatile boolean cancelled;

        PrioritisedTask(Runnable delegate, int priority, long areaKey, BalancedThreadPool.OrderedGroup owner) {
            this.delegate = delegate;
            this.priority = priority;
            this.areaKey = areaKey;
            this.owner = owner;
            this.createdAt = System.currentTimeMillis();
        }

        /** Effective priority with aging: every 5s, priority improves by 1 level. */
        public int effectivePriority() {
            long age = System.currentTimeMillis() - createdAt;
            int steps = (int) (age / 5_000);
            return Math.max(0, priority - steps);
        }

        @Override
        public void run() {
            if (!cancelled) {
                delegate.run();
            }
        }

        void cancel() {
            cancelled = true;
        }

        @Override
        public int compareTo(PrioritisedTask o) {
            return Integer.compare(this.effectivePriority(), o.effectivePriority());
        }
    }

    // ── Worker ──

    private final class Worker extends Thread {
        private final PriorityBlockingQueue<PrioritisedTask> queue;
        private final long holdTimeNanos;

        Worker(String threadName, long holdTimeNanos) {
            super(threadName);
            setDaemon(true);
            this.holdTimeNanos = holdTimeNanos;
            this.queue = new PriorityBlockingQueue<>(64);
        }

        void enqueue(PrioritisedTask task) {
            queue.offer(task);
            LockSupport.unpark(this);
        }

        @Override
        public void run() {
            while (running) {
                PrioritisedTask task = null;

                try {
                    task = queue.poll(holdTimeNanos / 1_000_000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (!running) break;
                }

                if (task == null) {
                    task = steal();
                }

                if (task == null) {
                    LockSupport.parkNanos(holdTimeNanos);
                    continue;
                }

                try {
                    task.run();
                } catch (Throwable t) {
                    System.err.println("[" + getName() + "] Task error: " + t.getMessage());
                } finally {
                    if (task.owner != null) {
                        task.owner.onComplete(task);
                    }
                }
            }
        }

        private PrioritisedTask steal() {
            for (Worker sibling : workers) {
                if (sibling == this || sibling.queue.isEmpty()) continue;
                PrioritisedTask stolen = sibling.queue.poll();
                if (stolen != null) return stolen;
            }
            return null;
        }
    }

    // ── PendingTask ──

    private static final class PendingTask {
        private final Queue<PrioritisedTask> chain = new ConcurrentLinkedQueue<>();

        PendingTask(PrioritisedTask head) {
            chain.add(head);
        }

        void append(PrioritisedTask task) {
            chain.add(task);
        }

        PrioritisedTask poll() {
            return chain.poll();
        }
    }

    // ── Builder ──

    /**
     * @deprecated Use {@link WorkerThreadPool.Builder} instead.
     */
    @Deprecated
    public static final class Builder {
        private String name = "AzureBranches-Worker";
        private long holdTimeNanos = 20_000_000L;
        private int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);

        @Deprecated
        public Builder name(String name) { this.name = name; return this; }
        @Deprecated
        public Builder holdTimeNanos(long ns) { this.holdTimeNanos = ns; return this; }
        @Deprecated
        public Builder threadCount(int n) { this.threadCount = Math.max(1, n); return this; }

        @Deprecated
        public BalancedThreadPool build() {
            return new BalancedThreadPool(name, holdTimeNanos, threadCount);
        }
    }

    /**
     * @deprecated Use {@link WorkerThreadPool#builder()} instead.
     */
    @Deprecated
    public static Builder builder() {
        return new Builder();
    }
}
