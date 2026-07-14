/*
 * AzureBranches — Async Region File IO
 *
 * Event-driven chunk IO with priority scheduling and area ordering.
 * Uses BalancedThreadPool (our Spottedleaf-inspired thread pool) instead
 * of raw ExecutorService, replacing busy-wait flush with CompletableFuture.
 *
 * ─────────────────────────────────────────────────────────────────────
 * Spottedleaf / Moonrise (MIT License)
 * Copyright (c) Spottedleaf
 * Original concepts: ChunkIOTask state machine, priority-driven IO,
 *   area-ordered scheduling via OrderedStreamGroup.
 * ─────────────────────────────────────────────────────────────────────
 */
package com.azurebranches.moonrise.io;

import com.azurebranches.moonrise.concurrent.BalancedThreadPool;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Event-driven async region file IO backed by {@link BalancedThreadPool}.
 *
 * <p>Each chunk gets a {@link ChunkIOTask} state machine. Tasks are
 * submitted to the thread pool's {@link BalancedThreadPool.OrderedGroup}
 * by chunk key, ensuring same-chunk reads and writes are serialized
 * while different chunks can process in parallel.
 */
public final class AsyncRegionFile {

    private final Path regionDir;
    private final Map<Long, ChunkIOTask> tasks = new ConcurrentHashMap<>();
    private final BalancedThreadPool ioPool;
    private final BalancedThreadPool.OrderedGroup ioGroup;
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private CompletableFuture<Void> drainFuture = CompletableFuture.completedFuture(null);

    public AsyncRegionFile(Path regionDir) {
        this.regionDir = regionDir;
        this.ioPool = BalancedThreadPool.builder()
            .name("AzureBranches-IO")
            .holdTimeNanos(25_000_000L) // 25ms hold time for IO
            .threadCount(Math.max(1, Runtime.getRuntime().availableProcessors() / 4))
            .build();
        this.ioGroup = ioPool.createGroup();
    }

    // ── Public API ──

    /**
     * Schedule an async read. If a read for the same chunk is already
     * in-flight, shares the existing future.
     */
    public CompletableFuture<CompoundTag> readAsync(long chunkKey, AgedPriority priority) {
        ChunkIOTask task = tasks.compute(chunkKey, (key, existing) -> {
            ChunkIOTask t = existing != null ? existing : new ChunkIOTask(chunkKey);
            if (existing == null) {
                enqueue(t);
            }
            return t;
        });
        return task.requestRead(priority);
    }

    /**
     * Schedule an async write. Multiple rapid writes to the same chunk
     * are coalesced at the ChunkIOTask level — only the latest data survives.
     */
    public CompletableFuture<Void> writeAsync(long chunkKey, CompoundTag data, AgedPriority priority) {
        ChunkIOTask task = tasks.compute(chunkKey, (key, existing) -> {
            ChunkIOTask t = existing != null ? existing : new ChunkIOTask(chunkKey);
            if (existing == null) {
                enqueue(t);
            }
            return t;
        });
        return task.requestWrite(data, priority);
    }

    /**
     * Non-blocking flush: returns a future that completes when ALL
     * currently enqueued IO tasks have finished.
     *
     * <p>No busy-wait: uses a drain-future that checks pendingCount
     * on each task completion.
     */
    public synchronized CompletableFuture<Void> flushAll() {
        if (pendingCount.get() == 0) {
            return CompletableFuture.completedFuture(null);
        }
        if (!drainFuture.isDone()) {
            return drainFuture; // already draining, share the future
        }
        drainFuture = new CompletableFuture<>();
        checkDrain();
        return drainFuture;
    }

    public int pendingTasks() {
        return pendingCount.get();
    }

    /** Shutdown the underlying thread pool. */
    public void shutdown() {
        ioPool.shutdown();
    }

    // ── Internal ──

    private void enqueue(ChunkIOTask task) {
        pendingCount.incrementAndGet();
        int priority = task.effectivePriority().effectiveLevel();
        ioGroup.queue(task.chunkKey, () -> {
            try {
                task.execute(regionDir);
            } catch (Exception e) {
                task.failAll(e);
            } finally {
                if (pendingCount.decrementAndGet() == 0) {
                    checkDrain();
                }
                // Cleanup idle tasks to prevent memory leak
                if (task.isIdle()) {
                    tasks.remove(task.chunkKey, task);
                }
            }
        }, priority);
    }

    private synchronized void checkDrain() {
        if (pendingCount.get() == 0 && drainFuture != null && !drainFuture.isDone()) {
            drainFuture.complete(null);
        }
    }

    // ── Chunk IO Task ──

    private static class ChunkIOTask {
        final long chunkKey;
        private volatile State state = State.IDLE;

        private CompoundTag writeData;
        private CompletableFuture<CompoundTag> readFuture;
        private CompletableFuture<Void> writeFuture;
        private AgedPriority effectivePriority = AgedPriority.NORMAL;

        enum State { IDLE, READING, WRITING, WRITE_AFTER_READ }

        ChunkIOTask(long chunkKey) {
            this.chunkKey = chunkKey;
        }

        AgedPriority effectivePriority() {
            return effectivePriority;
        }

        synchronized CompletableFuture<CompoundTag> requestRead(AgedPriority priority) {
            if (readFuture != null && !readFuture.isDone()) {
                return readFuture;
            }
            readFuture = new CompletableFuture<>();
            this.effectivePriority = AgedPriority.higherOf(this.effectivePriority, priority);
            return readFuture;
        }

        synchronized CompletableFuture<Void> requestWrite(CompoundTag data, AgedPriority priority) {
            this.writeData = data; // coalesce: only latest survives
            this.effectivePriority = AgedPriority.higherOf(this.effectivePriority, priority);
            if (writeFuture == null || writeFuture.isDone()) {
                writeFuture = new CompletableFuture<>();
            }
            if (state == State.IDLE) {
                state = State.WRITING;
            } else if (state == State.READING) {
                state = State.WRITE_AFTER_READ;
            }
            return writeFuture;
        }

        synchronized void execute(Path regionDir) throws IOException {
            effectivePriority = effectivePriority.aged();

            // Build path: r.<rx>.<rz>.dat (per-chunk files within region dir)
            int cx = (int) (chunkKey >> 32);
            int cz = (int) chunkKey;
            Path file = regionDir.resolve("c." + cx + "." + cz + ".dat");

            // Phase 1: Read
            if (readFuture != null && !readFuture.isDone()) {
                state = State.READING;
                CompoundTag read = null;
                if (Files.exists(file)) {
                    try (DataInputStream in = new DataInputStream(
                            new BufferedInputStream(Files.newInputStream(file)))) {
                        read = NbtIo.read(in);
                    }
                }
                readFuture.complete(read);
            }

            // Phase 2: Write
            if (state == State.WRITE_AFTER_READ || state == State.WRITING) {
                if (writeData != null) {
                    Files.createDirectories(file.getParent());
                    try (DataOutputStream out = new DataOutputStream(
                            new BufferedOutputStream(Files.newOutputStream(file)))) {
                        NbtIo.write(writeData, out);
                    }
                }
                if (writeFuture != null) {
                    writeFuture.complete(null);
                }
                writeData = null;
            }

            state = State.IDLE;
        }

        synchronized void failAll(Throwable t) {
            if (readFuture != null) readFuture.completeExceptionally(t);
            if (writeFuture != null) writeFuture.completeExceptionally(t);
            state = State.IDLE;
        }

        boolean isIdle() {
            return state == State.IDLE
                && (readFuture == null || readFuture.isDone())
                && (writeFuture == null || writeFuture.isDone());
        }
    }
}
