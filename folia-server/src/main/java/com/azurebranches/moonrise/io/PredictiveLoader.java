/*
 * AzureBranches — Predictive Chunk Loader
 *
 * Predicts future player positions based on velocity and pre-emptively
 * schedules chunk loads for the predicted area. Reduces perceived latency
 * when players move quickly (elytra flight, boat on ice, etc.).
 *
 * ─────────────────────────────────────────────────────
 * Spottedleaf / Moonrise (MIT License)
 * Copyright (c) Spottedleaf
 * Original concept: region-aware loading with priority scheduling.
 * Enhancement: velocity-based prediction + preload scheduling.
 * ─────────────────────────────────────────────────────
 */
package com.azurebranches.moonrise.io;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PredictiveLoader {

    private static final double PREDICTION_SECONDS = 2.0;
    private static final int PRELOAD_RADIUS_CHUNKS = 3;
    private static final double MIN_SPEED_FOR_PREDICTION = 4.0;

    private final MoonriseIO moonrise;
    private final Map<ServerPlayer, PlayerPrediction> playerState = new ConcurrentHashMap<>();

    public PredictiveLoader(MoonriseIO moonrise) {
        this.moonrise = moonrise;
    }

    public void onPlayerTick(ServerPlayer player) {
        Vec3 pos = player.position();
        Vec3 velocity = player.getDeltaMovement();

        PlayerPrediction prev = playerState.get(player);
        if (prev == null) {
            playerState.put(player, new PlayerPrediction(pos, velocity));
            return;
        }

        double speed = velocity.length();
        prev.update(pos, velocity);

        if ((float) speed < MIN_SPEED_FOR_PREDICTION) {
            return;
        }

        Vec3 predicted = pos.add(
            velocity.x * PREDICTION_SECONDS,
            0, 
            velocity.z * PREDICTION_SECONDS
        );

        if (prev.lastPredictedPos != null
            && prev.lastPredictedPos.distanceToSqr(predicted) < 64.0) { // 8 blocks
            return;
        }
        prev.lastPredictedPos = predicted;

        Set<Long> chunkKeys = collectChunkKeys(predicted, PRELOAD_RADIUS_CHUNKS);

        chunkKeys.removeIf(key -> prev.predictionKeys.contains(key));

        if (!chunkKeys.isEmpty()) {
            moonrise.batchRead(chunkKeys, AgedPriority.LOW);

            prev.predictionKeys.clear();
            prev.predictionKeys.addAll(chunkKeys);
        }
    }

    public void onPlayerQuit(ServerPlayer player) {
        playerState.remove(player);
    }

    public void preloadArea(ServerLevel level, int centerX, int centerZ, int radiusChunks) {
        Set<Long> keys = collectChunkKeys(new Vec3(centerX << 4, 0, centerZ << 4), radiusChunks);
        moonrise.batchRead(keys, AgedPriority.LOW); // LOW: background, ages up naturally
    }

    private static Set<Long> collectChunkKeys(Vec3 center, int radiusChunks) {
        int cx = ((int) center.x) >> 4;
        int cz = ((int) center.z) >> 4;
        Set<Long> keys = new HashSet<>();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                keys.add(((long) (cx + dx) << 32) | ((cz + dz) & 0xFFFFFFFFL));
            }
        }
        return keys;
    }

    private static class PlayerPrediction {
        Vec3 lastPos;
        Vec3 lastVelocity;
        Vec3 lastPredictedPos;
        final Set<Long> predictionKeys = new HashSet<>();

        PlayerPrediction(Vec3 pos, Vec3 vel) {
            this.lastPos = pos;
            this.lastVelocity = vel;
        }

        void update(Vec3 pos, Vec3 vel) {
            this.lastPos = pos;
            this.lastVelocity = vel;
        }
    }
}
