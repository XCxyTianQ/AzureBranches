/*
 * AzureBranches Moonrise IO — Async chunk IO subsystem
 *
 * Based on design patterns from PaperMC/Paper and Spottedleaf/Moonrise (MIT).
 * Busy-wait replaced with CompletableFuture; write debouncing,
 * region batching, priority aging, and predictive preloading added.
 *
 * ─────────────────────────────────────────────────────
 * Spottedleaf / Moonrise (MIT License)
 * Copyright (c) Spottedleaf
 * Original concept: asynchronous chunk IO with priority scheduling.
 * ─────────────────────────────────────────────────────
 */
package com.azurebranches.moonrise.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class MoonriseIO {

    private final ServerLevel level;
    private final AsyncRegionFile asyncIO;
    private final DebouncedWrites debouncer;
    private final RegionBatchIO batcher;
    private final PredictiveLoader predictor;

    public MoonriseIO(ServerLevel level, Path regionDir) {
        this.level = level;
        this.asyncIO = new AsyncRegionFile(regionDir);
        this.debouncer = new DebouncedWrites(this.asyncIO, 100_000_000L); // 100ms window
        this.batcher = new RegionBatchIO(this.asyncIO);
        this.predictor = new PredictiveLoader(this);
    }

    public CompletableFuture<CompoundTag> readChunk(long chunkKey, AgedPriority priority) {
        return asyncIO.readAsync(chunkKey, priority);
    }

    public CompletableFuture<Map<Long, CompoundTag>> batchRead(Set<Long> chunkKeys, AgedPriority priority) {
        return batcher.batchRead(chunkKeys, priority);
    }



    public void scheduleWrite(long chunkKey, CompoundTag data, AgedPriority priority) {
        debouncer.scheduleWrite(chunkKey, data, priority);
    }

    public void scheduleBatchWrite(Map<Long, CompoundTag> chunks, AgedPriority priority) {
        batcher.batchWrite(chunks, priority);
    }

    public CompletableFuture<Void> flushAsync() {
        return asyncIO.flushAll();
    }

    public PredictiveLoader predictor() {
        return predictor;
    }

    public ServerLevel level() {
        return level;
    }
}
