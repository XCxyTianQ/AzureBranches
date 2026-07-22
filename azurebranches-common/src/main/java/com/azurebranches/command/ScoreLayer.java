/*
 * AzureBranches - EXP4 Scoreboard Inverse-Operation Compensation
 *
 * During Phase rollback (OCC validation failure), scoreboard operations
 * are compensated via reverse-operation semantics rather than value
 * restoration. This prevents Silent Data Corruption when concurrent
 * chains or players have modified the same scores between the Phase's
 * writes and the rollback.
 *
 * Theoretical basis: Integer addition forms an abelian group (Z, +).
 * Every scoreboard operation sequence reduces to a net delta Δ, and
 * the inverse element -Δ always exists. Because the group is commutative,
 * compensation preserves all concurrent external modifications without
 * needing to know their identity, order, or count.
 *
 * Algorithm (Saga Compensation Pattern, Garcia-Molina & Salem 1987):
 *   For each score key modified by the failed Phase:
 *     1. Compute netDelta = cachedNewValue - firstOldValue
 *     2. Read currentScore from the live scoreboard
 *     3. Write currentScore - netDelta (the compensation target)
 *
 * Example:
 *   Pre-Phase:   score = 10
 *   Phase:       score += 5  →  cache=15, old=10, netDelta=+5
 *   Concurrent:  score += 3  (another chain, after Phase commit)
 *   Current:     10+5+3 = 18
 *   Compensation: 18 - 5 = 13  ✓ (concurrent +3 preserved)
 *
 *   If we used value restoration: score→10 → concurrent +3 lost! ✗
 *
 * Thread safety:
 *   compensate() is called from the home region thread during Phase rollback.
 *   The ScoreReader/ScoreWriter callbacks are expected to delegate to the
 *   Minecraft Scoreboard, which is thread-safe for the calling region.
 *
 * Zero Minecraft-internal imports by design — scoreboard integration is
 * provided via functional interfaces by the caller (build-time patch injection
 * into CommandBlock.java aggregateAndResume).
 */
package com.azurebranches.command;

import java.util.Map;
import java.util.Set;

public final class ScoreLayer {

    // ================================================================
    //  Functional interfaces (Minecraft Scoreboard integration)
    // ================================================================

    /**
     * Read the current score value for a holder under a specific objective.
     * <p>
     * Typical implementation (injected by build script patch):
     * <pre>
     *   (obj, h) -&gt; scoreboard.getOrCreatePlayerScore(
     *       h, scoreboard.getObjective(obj)).getScore()
     * </pre>
     */
    @FunctionalInterface
    public interface ScoreReader {
        int getScore(String objective, String holder);
    }

    /**
     * Write a score value for a holder under a specific objective.
     * <p>
     * Typical implementation (injected by build script patch):
     * <pre>
     *   (obj, h, v) -&gt; scoreboard.getOrCreatePlayerScore(
     *       h, scoreboard.getObjective(obj)).setScore(v)
     * </pre>
     */
    @FunctionalInterface
    public interface ScoreWriter {
        void setScore(String objective, String holder, int value);
    }

    // ================================================================
    //  Compensation algorithm
    // ================================================================

    /**
     * Compensate all scoreboard operations recorded in a failed Phase.
     * <p>
     * For each score key that was modified during the Phase, this method:
     * <ol>
     *   <li>Computes the net delta (cachedNew − firstOld)</li>
     *   <li>Reads the current live scoreboard value</li>
     *   <li>Writes (current − netDelta) back — cancelling the Phase's
     *       effect while preserving all concurrent modifications</li>
     * </ol>
     * <p>
     * This is a best-effort operation. Malformed keys are silently skipped.
     * If a score no longer exists (objective removed etc.), the key is skipped
     * and counted as a failure.
     *
     * @param snap   the PhaseSnapshot from the Phase being rolled back
     * @param reader reads current live scoreboard values
     * @param writer writes compensated values back to the scoreboard
     * @return number of score keys successfully compensated
     */
    public static int compensate(
        final PhaseSnapshot snap,
        final ScoreReader reader,
        final ScoreWriter writer
    ) {
        final Set<String> modifiedKeys = snap.scoreKeySet();
        if (modifiedKeys.isEmpty()) {
            return 0;
        }

        int compensated = 0;
        for (final String key : modifiedKeys) {
            final Map<String, Integer> oldScores = snap.getOldScoreValues();
            final Integer oldValue = oldScores.get(key);
            final Integer cachedNew = snap.getCachedScore(key);

            if (oldValue == null || cachedNew == null) {
                // Should not happen: scoreKeySet() is backed by oldScoreValues.
                // Defensive skip.
                continue;
            }

            final int netDelta = cachedNew - oldValue;
            if (netDelta == 0) {
                // No net change (e.g. add 5 then remove 5 within same Phase).
                // Nothing to compensate.
                continue;
            }

            // Parse "objective:holder" key format
            final int colon = key.indexOf(':');
            if (colon < 0) {
                // Malformed key — skip (shouldn't happen, defensive)
                ExpChainSupport.onScoreCompensationFailed();
                continue;
            }
            final String objective = key.substring(0, colon);
            final String holder = key.substring(colon + 1);
            if (objective.isEmpty() || holder.isEmpty()) {
                ExpChainSupport.onScoreCompensationFailed();
                continue;
            }

            // Read current live value from the scoreboard
            final int currentScore;
            try {
                currentScore = reader.getScore(objective, holder);
            } catch (final Exception e) {
                // Objective or holder may have been removed since Phase execution.
                // Compensation cannot proceed for this key.
                ExpChainSupport.onScoreCompensationFailed();
                continue;
            }

            // Apply inverse compensation: subtract the Phase's net effect
            final int target = currentScore - netDelta;
            try {
                writer.setScore(objective, holder, target);
                compensated++;
            } catch (final Exception e) {
                // Write failed — scoreboard state may be inconsistent.
                // Log and continue compensating remaining keys.
                System.err.println(
                    "[AzureBranches] ScoreLayer: failed to write compensation "
                    + "score=" + key + " target=" + target + ": " + e.getMessage());
                ExpChainSupport.onScoreCompensationFailed();
            }
        }

        if (compensated > 0) {
            ExpChainSupport.onScoreCompensated(compensated);
        }
        return compensated;
    }

    private ScoreLayer() {}
}
