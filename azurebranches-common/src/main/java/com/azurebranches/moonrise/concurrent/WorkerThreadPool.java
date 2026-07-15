/*
 * AzureBranches — Worker Thread Pool
 *
 * A general-purpose work-stealing thread pool with priority scheduling,
 * area ordering, and graceful lifecycle management.
 *
 * Upgraded from BalancedThreadPool with:
 *  - Lifecycle: init / shutdown / halt / join with timeout
 *  - Stats: submitted, executed, stolen, rejected counters
 *  - Configurable via builder
 *  - Graceful drain before forced halt
 *
 * ─────────────────────────────────────────────────────────────────────
 * Spottedleaf / concurrentutil (MIT License)
 * Copyright (c) Spottedleaf
 * Original concepts: work-stealing, priority queue, area-ordering,
 *   hold-time optimization, NUMA-aware placement.
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A priority-based, work-stealing thread pool for offloaded server work.
 *
 * <h3>Key Features</h3>
 * <ul>
 *   <li><b>Priority scheduling</b> — 6 levels with time-based aging</li>
 *   <li><b>Work stealing</b> — idle threads pull work from busy siblings</li>
 *   <li><b>Area ordering</b> — tasks within the same region serialize via
 *       {@link OrderedGroup}</li>
 *   <li><b>Graceful shutdown</b> — drain then halt with timeout</li>
 *   <li><b>Stats</b> — submitted, executed, stolen, rejected counters</li>
 * </ul>
 */
public final class WorkerThreadPool {

    public static final int PRIORITY_LEVELS = 6;

    // ── Lifecycle states ──
    private static final int STATE_RUNNING  = 0;
    private static final int STATE_DRAINING = 1;
    private static final int STATE_HALTED   = 2;

    private final String name;
    private final long holdTimeNanos;
    private final int threadCount;
    private final Worker[] workers;
    private final AtomicInteger nextWorker = new AtomicInteger(0);
    private volatile int lifecycleState = STATE_RUNNING;

    // ── Stats ──
    private final AtomicLong submittedCount  = new AtomicLong(0);
    private final AtomicLong executedCount   = new AtomicLong(0);
    private final AtomicLong stolenCount     = new AtomicLong(0);
    private final AtomicLong rejectedCount   = new AtomicLong(0);
    private final AtomicLong failedCount     = new AtomicLong(0);

    /**
     * @param name           pool name for thread naming
     * @param holdTimeNanos  how long a thread spin-waits before parking
     * @param threadCount    number of worker threads
     */
    public WorkerThreadPool(String name, long holdTimeNanos, int threadCount) {
        this.name = name;
        this.holdTimeNanos = holdTimeNanos;
        this.threadCount = threadCount;
        this.workers = new Worker[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new Worker(name + "-" + i, holdTimeNanos);
            workers[i].start();
        }
    }

    // ── Ordered Group (area-serialized execution) ──

    /**
     * Creates a new {@link OrderedGroup} backed by this pool.
     * Tasks with the same {@code areaKey} are serialized; different
     * keys may run in parallel.
     */
    public OrderedGroup createGroup() {
        return new OrderedGroup(this);
    }

    // ── Direct task submission ──

    /**
     * Submits a fire-and-forget task with default priority (3 = NORMAL).
     * Tasks are load-balanced across workers via round-robin.
     */
    public void execute(Runnable task) {
        execute(task, 3);
    }

    /**
     * Submits a fire-and-forget task with the given priority (0 = highest).
     */
    public void execute(Runnable task, int priority) {
        if (!acceptingTasks()) {
            rejectedCount.incrementAndGet();
            return;
        }
        submittedCount.incrementAndGet();
        int idx = nextWorker.getAndUpdate(i -> (i + 1) % workers.length);
        workers[idx].enqueue(new PrioritisedTask(task, priority, 0, null));
    }

    // ── Internal submit (used by OrderedGroup) ──

    void submit(PrioritisedTask task) {
        if (!acceptingTasks()) {
            rejectedCount.incrementAndGet();
            task.cancel();
            return;
        }
        submittedCount.incrementAndGet();
        int idx = nextWorker.getAndUpdate(i -> (i + 1) % workers.length);
        workers[idx].enqueue(task);
    }

    private boolean acceptingTasks() {
        return lifecycleState == STATE_RUNNING;
    }

    // ── Lifecycle ──

    /**
     * Graceful shutdown: stops accepting new tasks and drains the queue,
     * waiting up to {@code timeoutMs} for workers to finish.
     *
     * @return true if all threads joined within timeout
     */
    public boolean shutdown(long timeoutMs) {
        lifecycleState = STATE_DRAINING;

        // Wake all workers so they can see the state change
        for (Worker w : workers) {
            LockSupport.unpark(w);
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Worker w : workers) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                halt();
                return false;
            }
            try {
                w.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                halt();
                return false;
            }
        }
        lifecycleState = STATE_HALTED;
        return true;
    }

    /**
     * Immediate halt: interrupts all workers. Use when graceful shutdown
     * is not needed or has timed out.
     */
    public void halt() {
        lifecycleState = STATE_HALTED;
        for (Worker w : workers) {
            w.interrupt();
        }
    }

    /**
     * @return true if the pool is still accepting tasks
     */
    public boolean isRunning() {
        return lifecycleState == STATE_RUNNING;
    }

    /**
     * @return true if the pool has been halted
     */
    public boolean isHalted() {
        return lifecycleState == STATE_HALTED;
    }

    // ── Stats ──

    public long submittedCount()  { return submittedCount.get(); }
    public long executedCount()   { return executedCount.get(); }
    public long stolenCount()     { return stolenCount.get(); }
    public long rejectedCount()   { return rejectedCount.get(); }
    public long failedCount()     { return failedCount.get(); }

    /** Number of tasks currently queued across all workers. */
    public int pendingCount() {
        int total = 0;
        for (Worker w : workers) {
            total += w.queue.size();
        }
        return total;
    }

    public int threadCount() { return threadCount; }
    public String name()     { return name; }

    /** Combined stats string for logging/debugging. */
    public String stats() {
        return String.format(
            "[%s] submitted=%d exec=%d stolen=%d rejected=%d failed=%d pending=%d threads=%d",
            name,
            submittedCount(), executedCount(), stolenCount(),
            rejectedCount(), failedCount(), pendingCount(), threadCount()
        );
    }

    // ── Ordered Group ──

    /**
     * An ordered task group backed by a {@link WorkerThreadPool}.
     *
     * <p>Tasks submitted with the same {@code areaKey} are executed
     * sequentially in FIFO order; tasks with different keys may run
     * concurrently across the pool's workers.
     */
    public static final class OrderedGroup {
        private final WorkerThreadPool pool;
        private final Map<Long, PendingTask> areaQueues = new ConcurrentHashMap<>();
        private final Queue<PrioritisedTask> pendingHeads = new ConcurrentLinkedQueue<>();

        OrderedGroup(WorkerThreadPool pool) {
            this.pool = pool;
        }

        /**
         * Enqueue a task for the given area. If another task for the same
         * area is already in-flight, this task chains behind it.
         */
        public void queue(long areaKey, Runnable task, int priority) {
            PrioritisedTask pt = new PrioritisedTask(task, priority, areaKey, this);
            PendingTask existing = areaQueues.putIfAbsent(areaKey, new PendingTask(pt));
            if (existing == null) {
                // First task for this area — submit immediately
                pendingHeads.add(pt);
                pool.submit(pt);
            } else {
                // Chain behind the existing task
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

    // ── Prioritised Task ──

    /**
     * A task with priority, area key, and aging support.
     *
     * <p>Priority aging: every 5 seconds, the effective priority improves
     * by 1 level, preventing starvation of low-priority tasks.
     */
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

        /** Priority with aging: priority improves every 5 seconds. */
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
            while (lifecycleState != STATE_HALTED) {
                PrioritisedTask task = null;

                try {
                    task = queue.poll(holdTimeNanos / 1_000_000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (lifecycleState == STATE_HALTED) break;
                }

                if (task == null) {
                    task = steal();
                }

                if (task == null) {
                    if (lifecycleState == STATE_DRAINING) break;
                    LockSupport.parkNanos(holdTimeNanos);
                    continue;
                }

                try {
                    task.run();
                    executedCount.incrementAndGet();
                } catch (Throwable t) {
                    failedCount.incrementAndGet();
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
                if (stolen != null) {
                    stolenCount.incrementAndGet();
                    return stolen;
                }
            }
            return null;
        }
    }

    // ── Pending Task Chain ──

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

    public static final class Builder {
        private String name = "AzureBranches-Worker";
        private long holdTimeNanos = 20_000_000L; // 20ms
        private int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);

        public Builder name(String name)          { this.name = name; return this; }
        public Builder holdTimeNanos(long ns)     { this.holdTimeNanos = ns; return this; }
        public Builder threadCount(int n)         { this.threadCount = Math.max(1, n); return this; }

        public WorkerThreadPool build() {
            return new WorkerThreadPool(name, holdTimeNanos, threadCount);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
