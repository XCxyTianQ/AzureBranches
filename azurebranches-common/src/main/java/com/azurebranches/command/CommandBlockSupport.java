/*
 * AzureBranches - Command Block Support
 *
 * Folia disables command blocks entirely (BaseCommandBlock#performCommand is
 * short-circuited) because commands may touch data owned by other region
 * threads. AzureBranches re-opens that entry point behind a mode switch:
 *
 *   SAFE   - Folia default. Command blocks do not execute.
 *   ACCESS - Execute on the owning region thread. Cross-region effects are
 *            routed by Folia's per-command patches (queueOrExecuteTickTask,
 *            teleportAsync, region-aware selectors) and may complete
 *            asynchronously: same-region semantics are vanilla; cross-region
 *            success counts / chain conditions are best-effort.
 *   EXP    - Reserved for the coordinator-thread design (synchronous
 *            cross-region chain semantics). Not implemented yet; currently
 *            behaves like ACCESS and logs a one-time notice.
 *
 * Zero Minecraft-internal imports by design.
 */
package com.azurebranches.command;

import com.azurebranches.config.AzureBranchesConfig;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class CommandBlockSupport {

    public enum Mode { SAFE, ACCESS, EXP }

    private static volatile String cachedRaw = null;
    private static volatile Mode cachedMode = Mode.SAFE;
    private static volatile boolean expNoticeLogged = false;

    private static final AtomicLong containedFailures = new AtomicLong();
    private static final long LOG_EVERY = 100L;

    private CommandBlockSupport() {}

    public static Mode mode() {
        final String raw;
        try {
            raw = AzureBranchesConfig.get().commandBlocksMode();
        } catch (IllegalStateException e) {
            return Mode.SAFE; // config not initialised yet
        }
        final String cached = cachedRaw;
        if (cached != null && cached.equals(raw)) {
            return cachedMode;
        }
        Mode parsed;
        try {
            parsed = Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            System.err.println("[AzureBranches] command_blocks.mode: unknown value '" + raw + "', falling back to SAFE");
            parsed = Mode.SAFE;
        }
        cachedRaw = raw;
        cachedMode = parsed;
        return parsed;
    }

    /** Gate for BaseCommandBlock#performCommand. */
    public static boolean shouldRun() {
        final Mode mode = mode();
        if (mode == Mode.SAFE) {
            return false;
        }
        if (mode == Mode.EXP && !expNoticeLogged) {
            expNoticeLogged = true;
            System.out.println("[AzureBranches] command_blocks.mode=EXP: coordinator thread not implemented yet, behaving like ACCESS");
        }
        return true;
    }

    /**
     * Contain a command-block execution failure instead of letting it crash
     * the region thread. Returns true when the failure was contained.
     */
    public static boolean containFailure(final String command, final Throwable t) {
        if (mode() == Mode.SAFE) {
            return false; // not ours - keep vanilla crash-report behaviour
        }
        final long n = containedFailures.incrementAndGet();
        if (n == 1L || n % LOG_EVERY == 0L) {
            System.err.println("[AzureBranches] command block failed (contained, total " + n + "): '" + command + "' -> " + t);
            if (n == 1L) {
                t.printStackTrace();
            }
        }
        return true;
    }

    public static long containedFailureCount() {
        return containedFailures.get();
    }
}
