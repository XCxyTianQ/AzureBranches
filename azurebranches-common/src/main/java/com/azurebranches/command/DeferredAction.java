/*
 * AzureBranches - EXP4 DeferredAction: Entity Lifecycle Write-Ahead Log
 *
 * A lightweight record of an intended entity lifecycle operation
 * that is logged during Phase execution and applied only at Phase commit.
 * On rollback, all deferred actions in the Phase are simply discarded.
 *
 * This is the Write-Ahead Log (WAL) pattern for entity operations:
 *   - /kill   → log KILL, entity stays alive
 *   - /tp     → log TP, entity stays in place
 *   - /summon → log SUMMON, entity does not exist yet
 *
 * Phase commit: replay actions in order → real world effects
 * Phase rollback: discard log → never happened
 *
 * Design:
 *   - Three packed longs for type-specific data (no extra allocations)
 *   - Coordinates use float precision (sufficient for MC entity pos, cm-level)
 *   - Summon NBT lives in PhaseSnapshot.nbtCache, not copied here
 *   - Zero Minecraft-internal imports
 *
 * Thread safety: home region thread only.
 */
package com.azurebranches.command;

import java.util.Objects;

public final class DeferredAction {

    // ================================================================
    //  Action types (stable ordinals — do NOT reorder)
    // ================================================================

    public enum ActionType {
        /** Mark entity as dead. Not removed until commit. */
        KILL,

        /** Teleport entity to target. Not moved until commit. */
        TP,

        /**
         * Summon a new entity. Not created until commit.
         * NBT overrides live in PhaseSnapshot.nbtCache.
         */
        SUMMON
    }

    // ================================================================
    //  Fields
    // ================================================================

    public final ActionType type;
    public final int entityId;      // real ID (KILL/TP) or virtual ID (SUMMON)

    /**
     * Packed data 1:
     *   TP:     (floatBits(x) << 32) | floatBits(z)
     *   SUMMON: entityTypeOrdinal (low 32 bits)
     *   KILL:   unused
     */
    final long data1;

    /**
     * Packed data 2:
     *   TP:     floatBits(y) (high 32 bits)
     *   SUMMON: (floatBits(x) << 32) | floatBits(z)
     *   KILL:   unused
     */
    final long data2;

    /**
     * Packed data 3:
     *   TP:     (floatBits(yaw) << 32) | floatBits(pitch)
     *   SUMMON: floatBits(y) (high 32 bits)
     *   KILL:   unused
     */
    final long data3;

    // ================================================================
    //  Construction
    // ================================================================

    private DeferredAction(final ActionType type, final int entityId,
                           final long d1, final long d2, final long d3) {
        this.type = Objects.requireNonNull(type);
        this.entityId = entityId;
        this.data1 = d1;
        this.data2 = d2;
        this.data3 = d3;
    }

    // ================================================================
    //  Factory methods
    // ================================================================

    public static DeferredAction kill(final int entityId) {
        return new DeferredAction(ActionType.KILL, entityId, 0L, 0L, 0L);
    }

    /**
     * @param x/y/z target position (float precision, sufficient for MC)
     * @param yaw   horizontal rotation (degrees)
     * @param pitch vertical rotation (degrees)
     */
    public static DeferredAction tp(final int entityId,
                                     final float x, final float y, final float z,
                                     final float yaw, final float pitch) {
        return new DeferredAction(
            ActionType.TP, entityId,
            packFloatFloat(x, z),
            packFloat(y),
            packFloatFloat(yaw, pitch)
        );
    }

    /**
     * @param virtualId      temporary Phase-local ID (negative counter)
     * @param entityTypeOrd  EntityType ordinal
     * @param x/y/z          spawn position (float precision)
     */
    public static DeferredAction summon(final int virtualId, final int entityTypeOrd,
                                         final float x, final float y, final float z) {
        return new DeferredAction(
            ActionType.SUMMON, virtualId,
            (long) entityTypeOrd,          // low 32 bits only
            packFloatFloat(x, z),
            packFloat(y)
        );
    }

    // ================================================================
    //  Accessors
    // ================================================================

    public float tpX()  { return unpackHigh(data1); }
    public float tpZ()  { return unpackLow(data1); }
    public float tpY()  { return unpackHigh(data2); }
    public float tpYaw()   { return unpackHigh(data3); }
    public float tpPitch() { return unpackLow(data3); }

    public int summonTypeOrdinal() { return (int) data1; }
    public float summonX() { return unpackHigh(data2); }
    public float summonZ() { return unpackLow(data2); }
    public float summonY() { return unpackHigh(data3); }

    // ================================================================
    //  Bit packing (no method calls, just static helpers)
    // ================================================================

    private static long packFloatFloat(final float hi, final float lo) {
        return ((long) Float.floatToRawIntBits(hi) << 32)
             | (Float.floatToRawIntBits(lo) & 0xFFFF_FFFFL);
    }

    private static long packFloat(final float hi) {
        return (long) Float.floatToRawIntBits(hi) << 32;
    }

    private static float unpackHigh(final long packed) {
        return Float.intBitsToFloat((int) (packed >>> 32));
    }

    private static float unpackLow(final long packed) {
        return Float.intBitsToFloat((int) packed);
    }

    // ================================================================
    //  Functional interface for commit-time execution
    // ================================================================

    /**
     * Executes a deferred action against the real Minecraft world.
     * Injected by the build-script patch layer (CommandBlock.java).
     *
     * <p>Implementation sketch (in patch layer):</p>
     * <pre>
     *   executor = (action, snap) -&gt; {
     *       ServerLevel level = ...;
     *       switch (action.type) {
     *           case KILL -&gt; {
     *               Entity e = level.getEntity(action.entityId);
     *               if (e != null) e.remove(RemovalReason.KILLED);
     *           }
     *           case TP -&gt; {
     *               Entity e = level.getEntity(action.entityId);
     *               if (e != null) e.teleportAsync(
     *                   level, action.tpX(), action.tpY(), action.tpZ(),
     *                   action.tpYaw(), action.tpPitch());
     *           }
     *           case SUMMON -&gt; {
     *               EntityType type = BuiltInRegistries.ENTITY_TYPE.byId(
     *                   action.summonTypeOrdinal());
     *               Entity e = type.create(level);
     *               e.moveTo(action.summonX(), action.summonY(), action.summonZ());
     *               // Apply cached NBT from PhaseSnapshot
     *               applyNbtCache(snap, action.entityId, e);
     *               level.addFreshEntity(e);
     *           }
     *       }
     *   };
     * </pre>
     */
    @FunctionalInterface
    public interface Executor {
        /**
         * Commit one action to the real world.
         * @param action the deferred action
         * @param snap   PhaseSnapshot (for summon NBT cache lookup)
         */
        void execute(DeferredAction action, PhaseSnapshot snap);
    }

    // ================================================================
    //  Introspection
    // ================================================================

    @Override
    public String toString() {
        return switch (type) {
            case KILL   -> "KILL(eid=" + entityId + ")";
            case TP     -> String.format("TP(eid=%d → %.1f,%.1f,%.1f)",
                              entityId, tpX(), tpY(), tpZ());
            case SUMMON -> String.format("SUMMON(vid=%d type=%d → %.1f,%.1f,%.1f)",
                              entityId, summonTypeOrdinal(),
                              summonX(), summonY(), summonZ());
        };
    }
}
