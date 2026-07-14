/*
 * AzureBranches — Debounced (Coalesced) Writes
 *
 * Accumulates rapid writes to the same chunk within a configurable
 * time window, flushing only the latest data when the window expires.
 * Dramatically reduces disk IO for frequently-modified chunks.
 *
 * ─────────────────────────────────────────────────────
 * Spottedleaf / Moonrise (MIT License)
 * Copyright (c) Spottedleaf
 * Original concept: write merging in ChunkIOTask.
 * Enhancement: time-window debouncing with configurable window size.
 * ─────────────────────────────────────────────────────
 */
package com.azurebranches.moonrise.io;

import net.minecraft.nbt.CompoundTag;

import java.util.Map;
import java.util.concurrent.*;

public final class DebouncedWrites {

    private final AsyncRegionFile backend;
    private final long windowNanos;
    private final Map<Long, PendingWrite> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AzureBranches Debounce Timer");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param backend     
     * @param windowNanos 
     */
    public DebouncedWrites(AsyncRegionFile backend, long windowNanos) {
        this.backend = backend;
        this.windowNanos = windowNanos;
    }

    public void scheduleWrite(long chunkKey, CompoundTag data, AgedPriority priority) {
        PendingWrite pw = pending.compute(chunkKey, (key, existing) -> {
            if (existing != null) {
                existing.replace(data, priority);
                return existing;
            }
            return new PendingWrite(chunkKey, data, priority);
        });

        if (pw.isNew()) {
            timer.schedule(() -> flushOne(chunkKey), windowNanos, TimeUnit.NANOSECONDS);
        }
    }

    public CompletableFuture<Void> flushAllNow() {
        CompletableFuture<?>[] futures = pending.keySet().stream()
            .map(this::flushOne)
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    private CompletableFuture<Void> flushOne(long chunkKey) {
        PendingWrite pw = pending.remove(chunkKey);
        if (pw == null) return CompletableFuture.completedFuture(null);
        return backend.writeAsync(chunkKey, pw.data, pw.priority);
    }

    public int pendingCount() {
        return pending.size();
    }

    private static class PendingWrite {
        final long chunkKey;
        CompoundTag data;
        AgedPriority priority;
        volatile boolean isNew = true;

        PendingWrite(long chunkKey, CompoundTag data, AgedPriority priority) {
            this.chunkKey = chunkKey;
            this.data = data;
            this.priority = priority;
        }

        void replace(CompoundTag newData, AgedPriority newPriority) {
            this.data = newData;
            this.priority = AgedPriority.higherOf(this.priority, newPriority);
            this.isNew = false; // not new, just replaced
        }

        boolean isNew() {
            return isNew;
        }
    }
}
