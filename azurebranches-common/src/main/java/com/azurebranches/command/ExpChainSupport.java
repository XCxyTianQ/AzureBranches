/*
 * AzureBranches - EXP Chain Support
 *
 * Infrastructure for command_blocks.mode=EXP: suspendable / resumable command
 * block chains with true cross-region result semantics.
 *
 * Design (see docs / daily notes 2026-07-16):
 *  - A command block chain always runs on its home region thread (adjacent
 *    loaded chunks are always the same region: Folia merges nearby sections,
 *    gridExponent=4 => 16x16-chunk sections).
 *  - Only remote *effects* (far setblock / tp / summon, ...) leave the region.
 *    Folia routes them fire-and-forget, so vanilla-Folia success counts lie.
 *  - In EXP mode, patched "awaitable" commands register a pending
 *    CompletableFuture<Boolean> here while the command executes synchronously
 *    on the home thread. The chain walker (patch 0012) then SUSPENDS the chain
 *    and resumes it on the home region thread once all pending futures
 *    complete - nothing ever blocks.
 *
 * Threading contract:
 *  - openContext/closeContext/registerRemote: home region thread only
 *    (thread-local; called during synchronous command execution).
 *  - Futures may be completed from any region thread.
 *  - tryBeginChain/endChain: home region thread of that chain.
 *
 * Zero Minecraft-internal imports by design: levels are identity-keyed as
 * Object, block positions as long (BlockPos.asLong()).
 */
package com.azurebranches.command;

import com.azurebranches.config.AzureBranchesConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ExpChainSupport {

    // ---- pending remote results (thread-local, per command execution) ----

    private static final ThreadLocal<List<CompletableFuture<Boolean>>> PENDING = new ThreadLocal<>();

    /** Open a collection scope before dispatching a command block command (EXP only). */
    public static void openContext() {
        PENDING.set(new ArrayList<>(4));
    }

    /**
     * Called by awaitable-patched commands when they queue remote work.
     * Returns null when no EXP chain context is active (plain ACCESS /
     * player command / non-EXP execution) - callers must treat null as
     * "behave exactly like vanilla Folia".
     */
    public static CompletableFuture<Boolean> registerRemote() {
        final List<CompletableFuture<Boolean>> list = PENDING.get();
        if (list == null) {
            return null;
        }
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        list.add(future);
        return future;
    }

    /** Close the scope and return collected pending futures (possibly empty). */
    public static List<CompletableFuture<Boolean>> closeContext() {
        final List<CompletableFuture<Boolean>> list = PENDING.get();
        PENDING.remove();
        return list != null ? list : List.of();
    }

    public static boolean isContextActive() {
        return PENDING.get() != null;
    }

    // ---- in-flight chain guard (repeating heads re-trigger every tick) ----

    private record ChainKey(Object level, long headPos) {}

    private static final Set<ChainKey> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * Mark a chain as in flight. Returns false when the same head is already
     * running a suspended chain - the caller must skip this trigger.
     */
    public static boolean tryBeginChain(final Object level, final long headPos) {
        return IN_FLIGHT.add(new ChainKey(level, headPos));
    }

    public static void endChain(final Object level, final long headPos) {
        IN_FLIGHT.remove(new ChainKey(level, headPos));
    }

    /** Failsafe for level unload: drop all in-flight markers of a level. */
    public static void dropLevel(final Object level) {
        IN_FLIGHT.removeIf(key -> key.level() == level);
    }

    public static int inFlightCount() {
        return IN_FLIGHT.size();
    }

    // ---- success-count aggregation strategy (configurable) ----

    /**
     * Aggregation strategy for commands whose remote effects span multiple
     * regions (one future per remote region, e.g. /fill). Chain continuation
     * criterion is uniformly "aggregate > 0".
     */
    public enum SuccessCountMode {
        /** successCount += number of successful remote ops (vanilla-like "blocks changed"). */
        SUM,
        /** Strict: success only when every remote op succeeded. */
        ALL,
        /** Lenient: success when at least one remote op succeeded. */
        ANY
    }

    private static volatile String cachedModeRaw;
    private static volatile SuccessCountMode cachedSuccessMode = SuccessCountMode.SUM;

    public static SuccessCountMode successCountMode() {
        final String raw;
        try {
            raw = AzureBranchesConfig.get().expSuccessCountMode();
        } catch (IllegalStateException e) {
            return SuccessCountMode.SUM;
        }
        final String cached = cachedModeRaw;
        if (cached != null && cached.equals(raw)) {
            return cachedSuccessMode;
        }
        SuccessCountMode parsed;
        try {
            parsed = SuccessCountMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            System.err.println("[AzureBranches] command_blocks.exp.success_count_mode: unknown value '" + raw + "', falling back to SUM");
            parsed = SuccessCountMode.SUM;
        }
        cachedModeRaw = raw;
        cachedSuccessMode = parsed;
        return parsed;
    }

    /**
     * Aggregate remote results into a successCount contribution per the
     * configured mode. Null results (timeout / cancelled) count as failure.
     * The chain continues iff the returned value is &gt; 0.
     */
    public static int aggregate(final List<Boolean> results) {
        if (results.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (final Boolean b : results) {
            if (Boolean.TRUE.equals(b)) ok++;
        }
        return switch (successCountMode()) {
            case SUM -> ok;
            case ALL -> ok == results.size() ? ok : 0;
            case ANY -> ok > 0 ? 1 : 0;
        };
    }

    // ---- config / stats ----

    public static long remoteTimeoutMs() {
        try {
            return AzureBranchesConfig.get().expRemoteTimeoutMs();
        } catch (IllegalStateException e) {
            return 1000L;
        }
    }

    private static final AtomicLong suspended = new AtomicLong();
    private static final AtomicLong resumed = new AtomicLong();
    private static final AtomicLong timeouts = new AtomicLong();

    public static void onSuspend() { suspended.incrementAndGet(); }
    public static void onResume() { resumed.incrementAndGet(); }
    public static void onTimeout(final String command) {
        final long n = timeouts.incrementAndGet();
        if (n == 1L || n % 50L == 0L) {
            System.err.println("[AzureBranches] EXP chain remote result timeout (total " + n + "): '" + command + "'");
        }
    }

    public static long suspendedCount() { return suspended.get(); }
    public static long resumedCount() { return resumed.get(); }
    public static long timeoutCount() { return timeouts.get(); }

    private ExpChainSupport() {}
}
