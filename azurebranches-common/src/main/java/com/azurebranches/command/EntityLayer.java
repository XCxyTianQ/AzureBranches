/*
 * AzureBranches - EXP4 EntityLayer: NBT Table Partitioning
 *
 * Maps Minecraft entity NBT tags into five semantic categories, each with
 * its own compensation strategy for OCC rollback. This replaces the
 * "infinite recursive tree" problem with six fixed-dimension tables.
 *
 * Category mapping (see categorize()):
 *
 *   IDENTITY    — id, UUID, CustomName, Tags        → skip rollback
 *   NUMERIC     — Health, Air, Pos[0-2], Motion[0-2], etc. → Δ inverse-op
 *   VALUE       — ExplosionRadius, Fuse, NoGravity, etc.   → value restore
 *   SLOT        — Inventory{Slot:N}, ArmorItems{Slot:N}, etc. → per-slot Δ/restore
 *   RELATIONAL  — Passengers, Brain, Leash          → mark Phase irreversible
 *
 * Path stability for SLOT containers:
 *   Uses Slot tag value (e.g. "Inventory{Slot:0b}") instead of list index
 *   to avoid index drift when items are inserted or removed from the list.
 *
 * Lifecycle:
 *   Phase execution:
 *     interceptRead(snap, entityId, tagPath)  → check cache, record read-set
 *     interceptWrite(snap, entityId, tagPath, newVal, oldVal) → cache + old-value
 *
 *   Phase rollback (OCC conflict):
 *     compensate(snap, writer) → apply inverse operations per category
 *
 * Thread safety:
 *   All methods are called from the home region thread. No synchronization.
 *
 * Zero Minecraft-internal imports.
 */
package com.azurebranches.command;

import com.azurebranches.config.AzureBranchesConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EntityLayer {

    // ================================================================
    //  NBT Categories
    // ================================================================

    /**
     * Semantic category for an entity NBT tag, determining its
     * compensation strategy on Phase rollback.
     */
    public enum NbtCategory {
        /** Identity fields — not rolled back (UUID, type, name). */
        IDENTITY,

        /**
         * Numeric fields — delta inverse-operation compensation.
         * Supported: int, long, float, double, short, byte.
         * Compensation = currentValue - (newValue - oldValue).
         */
        NUMERIC,

        /**
         * Value fields — old-value restoration on rollback.
         * Used for config flags and low-frequency-change fields.
         */
        VALUE,

        /**
         * Container slots — per-slot stable-key tracking.
         * Uses Slot tag instead of list index for path stability.
         */
        SLOT,

        /**
         * Relational fields — references to other entities.
         * Cannot be rolled back; marks the Phase as irreversible.
         */
        RELATIONAL
    }

    // ================================================================
    //  Category registry
    // ================================================================

    /**
     * Static mapping from NBT tag name (top-level or parent-scoped)
     * to its semantic category.
     *
     * <p>Unknown tags default to VALUE (value restoration, safe fallback).
     * NUMERIC vector components (Pos[0], Motion[1], etc.) are resolved
     * by checking the parent tag (Pos, Motion, Rotation).</p>
     */
    private static final Map<String, NbtCategory> CATEGORY = new HashMap<>();

    static {
        // ── Identity: who this entity is ──
        put("id",       NbtCategory.IDENTITY);
        put("UUID",     NbtCategory.IDENTITY);
        put("CustomName", NbtCategory.IDENTITY);
        put("Tags",     NbtCategory.IDENTITY);

        // ── Numeric: life layer (run-time variables) ──
        put("Health",            NbtCategory.NUMERIC);
        put("AbsorptionAmount",  NbtCategory.NUMERIC);
        put("Fire",              NbtCategory.NUMERIC);
        put("Air",               NbtCategory.NUMERIC);
        put("HurtTime",          NbtCategory.NUMERIC);
        put("DeathTime",         NbtCategory.NUMERIC);
        put("FallDistance",      NbtCategory.NUMERIC);
        put("Age",               NbtCategory.NUMERIC);
        put("ForcedAge",         NbtCategory.NUMERIC);
        put("InLove",            NbtCategory.NUMERIC);
        put("ConversionTime",    NbtCategory.NUMERIC);
        put("AngerTime",         NbtCategory.NUMERIC);
        put("TicksFrozen",       NbtCategory.NUMERIC);

        // ── Numeric: spatial layer (position / motion vectors) ──
        // Base tag is NUMERIC; components (Pos[0], Motion[1], etc.)
        // are resolved dynamically in categorize().
        put("Pos",       NbtCategory.NUMERIC);
        put("Motion",    NbtCategory.NUMERIC);
        put("Rotation",  NbtCategory.NUMERIC);
        put("LookAngle", NbtCategory.NUMERIC);

        // ── Value: config layer (behavioural flags / params) ──
        put("ExplosionRadius",     NbtCategory.VALUE);
        put("Fuse",                NbtCategory.VALUE);
        put("NoGravity",           NbtCategory.VALUE);
        put("Silent",              NbtCategory.VALUE);
        put("PersistenceRequired", NbtCategory.VALUE);
        put("NoAI",                NbtCategory.VALUE);
        put("Invulnerable",        NbtCategory.VALUE);
        put("Glowing",             NbtCategory.VALUE);
        put("HasVisualFire",       NbtCategory.VALUE);
        put("CanPickUpLoot",       NbtCategory.VALUE);
        put("CustomNameVisible",   NbtCategory.VALUE);
        put("LeftHanded",          NbtCategory.VALUE);
        put("Aggressive",          NbtCategory.VALUE);
        put("NoPhysics",           NbtCategory.VALUE);
        put("ignited",             NbtCategory.VALUE);
        put("powered",             NbtCategory.VALUE);
        put("Sitting",             NbtCategory.VALUE);
        put("Saddle",              NbtCategory.VALUE);
        put("Tame",                NbtCategory.VALUE);
        put("CollarColor",         NbtCategory.VALUE);

        // ── Container slots ──
        put("Inventory",    NbtCategory.SLOT);
        put("ArmorItems",   NbtCategory.SLOT);
        put("HandItems",    NbtCategory.SLOT);
        put("EnderItems",   NbtCategory.SLOT);
        put("Items",        NbtCategory.SLOT);  // block entities (chest etc.)

        // ── Relational: entity references ──
        put("Passengers",   NbtCategory.RELATIONAL);
        put("Brain",        NbtCategory.RELATIONAL);
        put("Leash",        NbtCategory.RELATIONAL);
        put("Riding",       NbtCategory.RELATIONAL);
        put("AttachCapabilities", NbtCategory.RELATIONAL);
    }

    private static void put(final String tag, final NbtCategory cat) {
        CATEGORY.put(tag, cat);
    }

    // ================================================================
    //  Category resolver
    // ================================================================

    /**
     * Resolve the category for an NBT tag name (NOT a full path — just
     * the leaf-level tag or the top-level parent name).
     *
     * <p>For compound sub-paths like "Pos[0]" or "Inventory{Slot:0b}/id",
     * callers pass the leaf tag name. Vector index is handled separately.</p>
     *
     * @param tagName the NBT tag name (e.g. "Health", "Pos", "Inventory")
     * @return the resolved category, defaulting to VALUE for unknowns
     */
    public static NbtCategory categorize(final String tagName) {
        if (tagName == null || tagName.isEmpty()) {
            return NbtCategory.VALUE;
        }
        final NbtCategory cat = CATEGORY.get(tagName);
        return cat != null ? cat : NbtCategory.VALUE; // safe default
    }

    // ================================================================
    //  Key construction
    // ================================================================

    /**
     * Build a stable cache key for an NBT tag.
     *
     * <p>Formats:</p>
     * <pre>
     *   Simple:     "{entityId}:{tagName}"              → "123:Health"
     *   Slot:       "{entityId}:{slotKey}/{field}"      → "123:Inventory{Slot:0b}/id"
     *   Vector:     "{entityId}:{tagName}[{index}]"     → "123:Pos[0]"
     * </pre>
     *
     * @param entityId the entity's network ID
     * @param tagName  the NBT tag name at the entity level
     * @param slotKey  slot descriptor (e.g. "Inventory{Slot:0b}"), null for non-slot
     * @param subField the sub-field within a slot (e.g. "id", "Count"), null for non-slot
     * @return a stable composite key
     */
    public static String nbtKey(
        final int entityId,
        final String tagName,
        final String slotKey,
        final String subField
    ) {
        final StringBuilder sb = new StringBuilder(64);
        sb.append(entityId).append(':');
        if (slotKey != null && !slotKey.isEmpty()) {
            sb.append(slotKey);
            if (subField != null && !subField.isEmpty()) {
                sb.append('/').append(subField);
            }
        } else {
            sb.append(tagName);
        }
        return sb.toString();
    }

    // ================================================================
    //  NBT Reader/Writer (Minecraft integration via patch injection)
    // ================================================================

    /**
     * Read the live NBT value for a specific entity tag from the world.
     * Injected by the build-script patch layer.
     */
    @FunctionalInterface
    public interface NbtReader {
        /**
         * @param entityId the entity's network ID
         * @param path     the full NBT path (e.g. "Health", "Pos[0]", "Inventory[0].id")
         * @return the current value as a Number or String, or null if N/A
         */
        Object read(int entityId, String path);
    }

    /**
     * Write a value back to an entity's NBT in the live world.
     * Injected by the build-script patch layer.
     */
    @FunctionalInterface
    public interface NbtWriter {
        /**
         * @param entityId the entity's network ID
         * @param path     the full NBT path
         * @param value    the value to write (Number or String)
         */
        void write(int entityId, String path, Object value);
    }

    // ================================================================
    //  Read interception
    // ================================================================

    /**
     * Intercept an NBT read during Phase execution. Checks the PhaseSnapshot
     * cache first; on cache miss, records the read for OCC validation.
     *
     * @param snap     the current PhaseSnapshot
     * @param entityId the entity's network ID
     * @param tagName  the NBT tag being read
     * @param slotKey  slot descriptor, or null
     * @param subField sub-field name, or null
     * @param tick     current game tick for OCC read-set timestamp
     * @return the cached value, or null to signal "read from live entity"
     */
    public static Object interceptRead(
        final PhaseSnapshot snap,
        final int entityId,
        final String tagName,
        final String slotKey,
        final String subField,
        final long tick
    ) {
        if (snap == null || !isEntityLayerEnabled()) {
            return null; // no interception, read from live entity
        }

        final String key = nbtKey(entityId, tagName, slotKey, subField);

        // Check cache first
        final Object cached = snap.getNbtCached(key);
        if (cached != null) {
            ExpChainSupport.onNbtCacheHit();
            return cached;
        }

        // Cache miss — record the read for OCC validation
        snap.recordNbtRead(key, tick);
        return null; // caller should read from live entity
    }

    // ================================================================
    //  Write interception
    // ================================================================

    /**
     * Intercept an NBT write during Phase execution. Caches the new value
     * and records the old value for compensation on rollback.
     *
     * @param snap     the current PhaseSnapshot
     * @param entityId the entity's network ID
     * @param tagName  the NBT tag being written
     * @param slotKey  slot descriptor, or null
     * @param subField sub-field name, or null
     * @param newVal   the new value being written
     * @param oldVal   the old value before the write (for compensation)
     */
    public static void interceptWrite(
        final PhaseSnapshot snap,
        final int entityId,
        final String tagName,
        final String slotKey,
        final String subField,
        final Object newVal,
        final Object oldVal
    ) {
        if (snap == null || !isEntityLayerEnabled()) {
            return;
        }

        final NbtCategory cat = categorize(tagName);
        if (cat == NbtCategory.IDENTITY) {
            return; // identity fields are never rolled back
        }
        if (cat == NbtCategory.RELATIONAL) {
            snap.markIrreversible();
            return; // relational ops mark Phase irreversible
        }

        final String key = nbtKey(entityId, tagName, slotKey, subField);
        snap.putNbt(key, newVal, oldVal);
        ExpChainSupport.onNbtInterceptWrite();
    }

    // ================================================================
    //  Compensation (Phase rollback)
    // ================================================================

    /**
     * Compensate all intercepted NBT writes when a Phase is rolled back.
     *
     * <p>Compensation strategy per category:</p>
     * <ul>
     *   <li>NUMERIC: delta inverse-op (current − (new − old))</li>
     *   <li>VALUE: value restoration (write old value)</li>
     *   <li>SLOT: per-entry (contained NUMERIC/VALUE fields handled individually)</li>
     *   <li>IDENTITY/RELATIONAL: skipped (RELATIONAL ops already marked Phase irreversible)</li>
     * </ul>
     *
     * @param snap   the PhaseSnapshot from the failed Phase
     * @param reader reads live values for delta computation
     * @param writer writes compensated values back to entities
     * @return number of keys compensated
     */
    public static int compensate(
        final PhaseSnapshot snap,
        final NbtReader reader,
        final NbtWriter writer
    ) {
        final Set<String> modifiedKeys = snap.nbtKeySet();
        if (modifiedKeys.isEmpty()) {
            return 0;
        }

        int compensated = 0;
        for (final String key : modifiedKeys) {
            final Object oldVal = snap.getNbtOldValue(key);
            final Object cachedNew = snap.getNbtCached(key);

            if (oldVal == null || cachedNew == null) {
                continue;
            }

            // Parse key: "entityId:tagName[/subField]" or "entityId:slotKey/subField"
            final int colon = key.indexOf(':');
            if (colon < 0) continue;
            final String entityPart = key.substring(0, colon);
            final String pathPart = key.substring(colon + 1);

            final int entityId;
            try {
                entityId = Integer.parseInt(entityPart);
            } catch (final NumberFormatException e) {
                continue;
            }

            // Determine category from the path
            // Path can be: "tagName" or "tagName[index]" or "slotKey/subField"
            final String effectiveTag = extractTagName(pathPart);
            final NbtCategory cat = categorize(effectiveTag);

            try {
                switch (cat) {
                    case NUMERIC -> {
                        if (compensateNumeric(key, pathPart, entityId, oldVal, cachedNew, reader, writer)) {
                            compensated++;
                        }
                    }
                    case VALUE -> {
                        // Value restoration: write old value directly
                        writer.write(entityId, pathPart, oldVal);
                        compensated++;
                    }
                    case SLOT -> {
                        // SLOT entries are sub-paths (e.g. "Inventory{Slot:0b}/id")
                        // Each SLOT sub-field is individually categorized
                        // We handle them here with delta/value logic
                        if (compensateSlotField(key, pathPart, entityId, oldVal, cachedNew, reader, writer)) {
                            compensated++;
                        }
                    }
                    default -> {
                        // IDENTITY/RELATIONAL: skip
                    }
                }
            } catch (final Exception e) {
                System.err.println(
                    "[AzureBranches] EntityLayer: failed to compensate NBT key='"
                    + key + "': " + e.getMessage());
                ExpChainSupport.onNbtCompensationFailed();
            }
        }

        if (compensated > 0) {
            ExpChainSupport.onNbtCompensated(compensated);
        }
        return compensated;
    }

    // ================================================================
    //  Private helpers
    // ================================================================

    /**
     * Extract the tag name from a path like "Pos[0]" or "Inventory{Slot:0b}/id".
     */
    private static String extractTagName(final String path) {
        final int bracket = path.indexOf('[');
        final int brace = path.indexOf('{');
        final int slash = path.indexOf('/');

        if (brace >= 0) {
            // Slot path: "Inventory{Slot:0b}/id" → top-level is before "{"
            return path.substring(0, brace);
        }
        if (bracket >= 0) {
            // Vector path: "Pos[0]" → tag is before "["
            return path.substring(0, bracket);
        }
        if (slash >= 0) {
            return path.substring(0, slash);
        }
        return path;
    }

    /**
     * Compensate a NUMERIC tag using delta inverse-operation.
     *
     * @return true on success
     */
    private static boolean compensateNumeric(
        final String key,
        final String path,
        final int entityId,
        final Object oldVal,
        final Object newVal,
        final NbtReader reader,
        final NbtWriter writer
    ) {
        // Only Number types support delta compensation
        if (!(oldVal instanceof Number) || !(newVal instanceof Number)) {
            // Fall back to value restoration for non-numeric values
            writer.write(entityId, path, oldVal);
            return true;
        }

        final double oldD = ((Number) oldVal).doubleValue();
        final double newD = ((Number) newVal).doubleValue();
        final double delta = newD - oldD;

        if (Math.abs(delta) < 1e-9) {
            return false; // no net change
        }

        // Read current live value
        final Object currentObj = reader.read(entityId, path);
        if (currentObj == null || !(currentObj instanceof Number)) {
            // Can't read current; fall back to value restoration
            writer.write(entityId, path, oldVal);
            return true;
        }

        final double currentD = ((Number) currentObj).doubleValue();
        final double target = currentD - delta;

        writer.write(entityId, path, target);
        return true;
    }

    /**
     * Compensate a SLOT sub-field. The compensation strategy depends on
     * the sub-field's type (Count/amount → NUMERIC delta; id/type → VALUE restore).
     */
    private static boolean compensateSlotField(
        final String key,
        final String path,
        final int entityId,
        final Object oldVal,
        final Object newVal,
        final NbtReader reader,
        final NbtWriter writer
    ) {
        // Slot sub-fields that are numeric (Count, Damage, etc.) use delta
        // String fields (id, Name) use value restoration
        final boolean isNumeric = (oldVal instanceof Number) && (newVal instanceof Number);
        final boolean isString = (oldVal instanceof String) && (newVal instanceof String);

        if (isNumeric) {
            return compensateNumeric(key, path, entityId, oldVal, newVal, reader, writer);
        } else if (isString) {
            // String: value restoration only (no "subtraction" for strings)
            // But check if the value actually changed
            if (oldVal.equals(newVal)) {
                return false;
            }
            writer.write(entityId, path, oldVal);
            return true;
        }

        // Unknown type: value restoration
        writer.write(entityId, path, oldVal);
        return true;
    }

    // ================================================================
    //  Configuration
    // ================================================================

    private static Boolean cachedEntityLayerEnabled;
    private static long cachedEntityLayerCheckMs;

    /**
     * Whether EntityLayer NBT interception is enabled in the config.
     * Cached for 5 seconds between checks.
     */
    public static boolean isEntityLayerEnabled() {
        final long now = System.currentTimeMillis();
        if (cachedEntityLayerEnabled != null && now - cachedEntityLayerCheckMs < 5000L) {
            return cachedEntityLayerEnabled;
        }
        try {
            cachedEntityLayerEnabled = AzureBranchesConfig.get().exp4EntityLayerEnabled();
        } catch (final IllegalStateException e) {
            cachedEntityLayerEnabled = true;
        }
        cachedEntityLayerCheckMs = now;
        return cachedEntityLayerEnabled;
    }

    // ================================================================
    //  Descriptor
    // ================================================================

    /** Human-readable status. */
    public static String describe() {
        final boolean enabled = isEntityLayerEnabled();
        return "EXP4 EntityLayer: " + (enabled ? "enabled" : "disabled")
            + " | categories=" + CATEGORY.size() + " tags mapped"
            + " | NUMERIC(Δ) VALUE(restore) SLOT(stable-key)";
    }

    private EntityLayer() {}
}
