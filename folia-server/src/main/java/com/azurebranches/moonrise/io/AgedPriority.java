/*
 * AzureBranches — Aged Priority (anti-starvation)
 *
 * Priority levels that automatically increase over time to prevent
 * low-priority tasks from being starved indefinitely.
 *
 * ─────────────────────────────────────────────────────
 * Spottedleaf / Moonrise (MIT License)
 * Copyright (c) Spottedleaf
 * Original concept: Priority enum with BLOCKING→COMPLETING levels.
 * Enhancement: automatic aging to prevent starvation.
 * ─────────────────────────────────────────────────────
 */
package com.azurebranches.moonrise.io;

public enum AgedPriority {

    BLOCKING (0,  "BLOCKING"),
    HIGHEST  (1,  "HIGHEST"),
    HIGH     (2,  "HIGH"),
    NORMAL   (3,  "NORMAL"),
    LOW      (4,  "LOW"),
    LOWEST   (5,  "LOWEST");

    public static final long AGE_INTERVAL_MS = 5_000;

    private final int level;
    private final String label;
    private final long createdAt;

    AgedPriority(int level, String label) {
        this.level = level;
        this.label = label;
        this.createdAt = System.currentTimeMillis();
    }

    public int effectiveLevel() {
        long age = System.currentTimeMillis() - createdAt;
        int steps = (int) (age / AGE_INTERVAL_MS);
        return Math.max(0, level - steps);
    }

    public boolean isHigherThan(AgedPriority other) {
        return this.effectiveLevel() < other.effectiveLevel();
    }

    public AgedPriority aged() {
        return this;
    }
    
    public static AgedPriority higherOf(AgedPriority a, AgedPriority b) {
        return a.effectiveLevel() <= b.effectiveLevel() ? a : b;
    }

    @Override
    public String toString() {
        return label + "(age=" + (System.currentTimeMillis() - createdAt) / 1000 + "s)";
    }
}
