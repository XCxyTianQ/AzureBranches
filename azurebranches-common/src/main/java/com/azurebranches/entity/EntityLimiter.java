/*
 * AzureBranches — Entity Tick Limiter
 *
 * Per-region, per-type entity tick throttling with fair round-robin.
 * Uses only Bukkit/Paper API — no Minecraft internal imports.
 *
 * Original concept: Kaiiju (dev.kaiijumc.kaiiju) — per-region entity
 * limits with round-robin tick distribution.
 * Build pattern credit: Luminol (AzureSkyline) by EarthMe & contributors.
 */
package com.azurebranches.entity;

import com.azurebranches.config.AzureBranchesConfig;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.*;

public final class EntityLimiter {

    private static EntityLimiter INSTANCE;

    private boolean enabled;
    private LimitEntry[] entries;
    private final Map<EntityType, LimitEntry> byType = new EnumMap<>(EntityType.class);
    private final Map<Object, PerRegionState> regionMap = new IdentityHashMap<>();
    private final Object regionLock = new Object();

    private EntityLimiter() {}

    public static synchronized EntityLimiter init() {
        if (INSTANCE != null) return INSTANCE;
        INSTANCE = new EntityLimiter();
        INSTANCE.loadConfig();
        return INSTANCE;
    }

    public static EntityLimiter get() { return INSTANCE; }
    public boolean isEnabled() { return enabled && INSTANCE != null; }

    private void loadConfig() {
        AzureBranchesConfig cfg = AzureBranchesConfig.get();
        this.enabled = cfg.entityLimitsEnabled();
        if (!enabled) return;

        Map<String, Map<String, Object>> types = cfg.entityLimitTypes();
        Map<String, LimitEntry> parsed = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, Object>> entry : types.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> val = entry.getValue();

            int limit = toInt(val.get("limit"), -1);
            int removal = toInt(val.get("removal"), -1);

            if (limit < 1) {
                System.err.println("[AzureBranches] entity_limits: '" + key + "' limit < 1, ignoring");
                continue;
            }
            if (removal != -1 && removal <= limit) {
                removal = limit * 10;
            }

            NamespacedKey nk = NamespacedKey.fromString(key);
            if (nk == null) {
                System.err.println("[AzureBranches] entity_limits: invalid key '" + key + "', ignoring");
                continue;
            }

            EntityType type = EntityType.fromName(nk.getKey());
            if (type == null) {
                // Try exact name match
                try { type = EntityType.valueOf(nk.getKey().toUpperCase()); }
                catch (IllegalArgumentException e) {
                    System.err.println("[AzureBranches] entity_limits: unknown type '" + key + "', ignoring");
                    continue;
                }
            }

            parsed.put(key, new LimitEntry(parsed.size(), type, limit, removal));
        }

        this.entries = parsed.values().toArray(new LimitEntry[0]);
        for (LimitEntry e : entries) byType.put(e.type, e);

        System.out.println("[AzureBranches] EntityLimiter: loaded " + entries.length + " type limit(s)");
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Long) return ((Long) v).intValue();
        if (v instanceof Double) return ((Double) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        return def;
    }

    private PerRegionState getOrCreateState(Object region) {
        PerRegionState state = regionMap.get(region);
        if (state == null) {
            synchronized (regionLock) {
                state = regionMap.get(region);
                if (state == null) {
                    state = new PerRegionState(entries.length);
                    regionMap.put(region, state);
                }
            }
        }
        return state;
    }

    public void onRegionTickStart(Object region) {
        if (!enabled || entries.length == 0) return;
        PerRegionState s = regionMap.get(region);
        if (s != null) s.reset();
    }

    public void onRegionTickEnd(Object region) {
        if (!enabled || entries.length == 0) return;
        PerRegionState s = regionMap.get(region);
        if (s != null) s.advanceAll(entries);
    }

    public void onRegionRemoved(Object region) {
        regionMap.remove(region);
    }

    public Decision check(Entity entity, Object region) {
        if (!enabled || entries.length == 0 || !entity.isValid()) {
            return Decision.TICK;
        }

        LimitEntry entry = byType.get(entity.getType());
        if (entry == null) return Decision.TICK;

        PerRegionState state = getOrCreateState(region);
        TickState ts = state.states[entry.id];
        int idx = ts.count++;

        if (entry.removal > 0 && idx >= entry.removal) {
            return Decision.REMOVE;
        }
        if (idx < ts.start || idx >= ts.start + entry.limit) {
            return Decision.SKIP;
        }
        return Decision.TICK;
    }

    public enum Decision { TICK, SKIP, REMOVE }

    public int typeCount() { return entries != null ? entries.length : 0; }

    static final class LimitEntry {
        final int id;
        final EntityType type;
        final int limit;
        final int removal;
        LimitEntry(int id, EntityType type, int limit, int removal) {
            this.id = id; this.type = type; this.limit = limit; this.removal = removal;
        }
    }

    static final class PerRegionState {
        final TickState[] states;
        PerRegionState(int size) {
            states = new TickState[size];
            for (int i = 0; i < size; i++) states[i] = new TickState();
        }
        void reset() { for (TickState s : states) s.count = 0; }
        void advanceAll(LimitEntry[] entries) {
            for (int i = 0; i < states.length; i++) {
                TickState s = states[i];
                int total = s.count;
                s.start = total > 0 ? (s.start + entries[i].limit) % total : 0;
            }
        }
    }

    static final class TickState { int count, start; }
}
