/*
 * AzureBranches — Balanced Thread Pool with Priority Scheduling
 *
 * A work-stealing thread pool with priority-based,
 * area-ordered task execution. Designed for chunk system workloads
 * where tasks should be (a) prioritized, (b) ordered within a region,
 * and (c) load-balanced across cores.
 *
 * ─────────────────────────────────────────────────────────────────────
 * Spottedleaf / concurrentutil (MIT License)
 * Copyright (c) Spottedleaf
 * Original concepts: BalancedPrioritisedThreadPool, OrderedStreamGroup,
 *   AreaDependentQueue, priority scheduling, queue-hold-time optimization,
 *   NUMA-aware thread placement.
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

public final class BalancedThreadPool {

    private static final int PRIORITY_LEVELS = 6;

    private final String name;
    private final long holdTimeNanos;
    private final Worker[] workers;
    private final AtomicInteger nextWorker = new AtomicInteger(0);
    private volatile boolean running = true;

    /**
     * @param holdTimeNanos how long a thread should spin-wait for new tasks
     *                      before parking (e.g. 20_000_000 = 20ms).
     * @param threadCount   initial number of worker threads
     */
    public BalancedThreadPool(String name, long holdTimeNanos, int threadCount) {
        this.name = name;
        this.holdTimeNanos = holdTimeNanos;
        this.workers = new Worker[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new Worker(name + "-" + i, holdTimeNanos);
            workers[i].start();
        }
    }

    public OrderedGroup createGroup() {
        return new OrderedGroup(this);
    }

    public void shutdown() {
        running = false;
        for (Worker w : workers) {
            LockSupport.unpark(w);
        }
    }

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

    public void halt() {
        running = false;
        for (Worker w : workers) {
            w.interrupt();
        }
    }

    public void adjustThreadCount(int newCount) {
        // TODO
    }

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

    public static final class OrderedGroup {
        private final BalancedThreadPool pool;
        private final Map<Long, PendingTask> areaQueues = new ConcurrentHashMap<>();
        private final Queue<PrioritisedTask> pendingHeads = new ConcurrentLinkedQueue<>();

        OrderedGroup(BalancedThreadPool pool) {
            this.pool = pool;
        }

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

    public static final class PrioritisedTask implements Runnable, Comparable<PrioritisedTask> {
        final Runnable delegate;
        final int priority;
        final long areaKey;
        final long createdAt;
        final OrderedGroup owner;
        volatile boolean cancelled;

        PrioritisedTask(Runnable delegate, int priority, long areaKey, OrderedGroup owner) {
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
            LockSupport.unpark(this); // wake if parked
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

    public static final class Builder {
        private String name = "AzureBranches-Worker";
        private long holdTimeNanos = 20_000_000L; // 20ms
        private int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);

        public Builder name(String name) { this.name = name; return this; }
        public Builder holdTimeNanos(long ns) { this.holdTimeNanos = ns; return this; }
        public Builder threadCount(int n) { this.threadCount = Math.max(1, n); return this; }

        public BalancedThreadPool build() {
            return new BalancedThreadPool(name, holdTimeNanos, threadCount);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
