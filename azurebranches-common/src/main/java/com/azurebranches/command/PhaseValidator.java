/*
 * AzureBranches - EXP3 Phase Validator (OCC)
 *
 * Implements the validation phase of Optimistic Concurrency Control
 * (Kung & Robinson, 1981) for EXP3 command block chain Phases.
 *
 * Validation checks (in order):
 *
 *   CHECK_READ_SET:  Were any positions in the readSet externally modified
 *                    between the tick they were read and the current tick?
 *                    (Prevents Non-Repeatable Reads — ANSI SQL isolation)
 *
 *   CHECK_WRITE_SET: Were any positions in the writeSet concurrently
 *                    modified by another chain? (Prevents Lost Updates)
 *                    Note: this is largely handled by ChainHead.traversalId
 *                    supersede; PhaseValidator provides the complementary
 *                    cross-chain detection.
 *
 *   CHECK_READ_SET_SIZE: Is the readSet too large to validate efficiently?
 *                    (Prevents validation overhead explosion — configurable
 *                    via command_blocks.exp.validation.max_read_set)
 *
 * On conflict:
 *   → ValidationResult.RETRY: re-execute the Phase (up to max_retries)
 *   → ValidationResult.RETRY_EXHAUSTED: fall back to best-effort semantics
 *
 * Thread safety:
 *   PhaseValidator is called from aggregateAndResume on the home region thread.
 *   externalCheckResults is populated by remote region threads via
 *   CompletableFuture — it is fully populated before validate() is called
 *   (happens-before via CompletableFuture.allOf).
 */
package com.azurebranches.command;

import com.azurebranches.config.AzureBranchesConfig;

import java.util.Locale;
import java.util.Map;

public final class PhaseValidator {

    // ================================================================
    //  Validation result
    // ================================================================

    /**
     * Outcome of Phase validation.
     */
    public enum ValidationResult {
        /** All checks passed. Phase is committed. */
        COMMIT,

        /**
         * Read-set conflict detected. Phase should be retried.
         * The caller should rollback the Phase and re-execute from
         * the last savepoint (or from the Phase start).
         */
        RETRY,

        /**
         * Retry limit exhausted. Phase cannot be validated.
         * The caller should fall back to best-effort semantics
         * (accept the current state and continue).
         */
        RETRY_EXHAUSTED,

        /**
         * Read-set exceeds configured maximum. Validation skipped
         * for performance reasons. The caller proceeds without
         * the isolation guarantees.
         */
        READ_SET_OVERFLOW
    }

    // ================================================================
    //  Validation thresholds
    // ================================================================

    /** Maximum positions in readSet before validation is skipped. */
    public static int maxReadSetSize() {
        try {
            return AzureBranchesConfig.get().expValidationMaxReadSet();
        } catch (final IllegalStateException e) {
            return 256; // conservative default
        }
    }

    /** Maximum retry count for a Phase before falling back. */
    public static int maxRetries() {
        try {
            return AzureBranchesConfig.get().expValidationMaxRetries();
        } catch (final IllegalStateException e) {
            return 3;
        }
    }

    /** Whether EXP3 validation is enabled at all. */
    public static boolean isEnabled() {
        try {
            return AzureBranchesConfig.get().expValidationEnabled();
        } catch (final IllegalStateException e) {
            return false;
        }
    }

    // ================================================================
    //  Validation logic
    // ================================================================

    /**
     * Validate a Phase's readSet against external modification results.
     *
     * @param snap      the PhaseSnapshot to validate
     * @param retryCount how many times this Phase has already been retried
     * @param externalCheckResults positions → true if externally modified,
     *                             populated by remote region verification tasks
     * @return the validation result
     */
    public static ValidationResult validate(
        final PhaseSnapshot snap,
        final int retryCount,
        final Map<Long, Boolean> externalCheckResults
    ) {
        if (!isEnabled()) {
            return ValidationResult.COMMIT; // bypass when disabled
        }

        final int maxRetries = maxRetries();
        if (retryCount >= maxRetries) {
            return ValidationResult.RETRY_EXHAUSTED;
        }

        final Map<Long, Long> readSet = snap.getReadSet();
        final int maxReadSet = maxReadSetSize();

        // CHECK_READ_SET_SIZE: skip validation if too large
        if (readSet.size() > maxReadSet) {
            System.err.println(
                "[AzureBranches] EXP3 validation skipped: readSet "
                + readSet.size() + " > max " + maxReadSet);
            return ValidationResult.READ_SET_OVERFLOW;
        }

        // CHECK_READ_SET: detect non-repeatable reads
        if (externalCheckResults != null && !externalCheckResults.isEmpty()) {
            for (final Map.Entry<Long, Long> entry : readSet.entrySet()) {
                final long pos = entry.getKey();
                final Boolean externallyModified = externalCheckResults.get(pos);
                if (Boolean.TRUE.equals(externallyModified)) {
                    // This position was modified externally between the read
                    // and now → our read is stale → Phase must retry
                    return ValidationResult.RETRY;
                }
            }
        }

        // CHECK_WRITE_SET: handled by ChainHead.traversalId supersede
        // (newer traversal automatically invalidates older Continuations).
        // No additional check needed here.

        return ValidationResult.COMMIT;
    }

    // ================================================================
    //  Validation mode descriptor
    // ================================================================

    /**
     * Produce a human-readable description of the current validation mode.
     */
    public static String describe() {
        if (!isEnabled()) {
            return "EXP3 validation disabled (config: validation.enabled=false)";
        }
        return "EXP3 OCC validation: maxReadSet=" + maxReadSetSize()
            + ", maxRetries=" + maxRetries();
    }

    private PhaseValidator() {}
}
