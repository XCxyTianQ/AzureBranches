/*
 * AzureBranches — Plugin entry point
 *
 * Initializes EntityLimiter and runs per-tick entity throttling
 * via Folia's GlobalRegionScheduler. Uses Paper's Mob.setAware(false)
 * to skip entity AI (equivalent to SKIP decision).
 */
package com.azurebranches.plugin;

import com.azurebranches.config.AzureBranchesConfig;
import com.azurebranches.entity.EntityLimiter;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class AzureBranchesPlugin extends JavaPlugin {

    private EntityLimiter limiter;
    private final Set<java.util.UUID> skippedThisTick = new HashSet<>();

    @Override
    public void onEnable() {
        AzureBranchesConfig.init(getDataFolder().toPath().getParent());
        limiter = EntityLimiter.init();
        if (!limiter.isEnabled()) {
            getLogger().info("EntityLimiter disabled in config");
            return;
        }
        getLogger().info("EntityLimiter enabled with " + limiter.typeCount() + " type limits");

        // Run every tick on the global region
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> tick(), 1L, 1L);
    }

    private void tick() {
        // Restore AI for entities that were skipped last tick
        for (java.util.UUID id : skippedThisTick) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof Mob mob && !e.isDead()) mob.setAware(true);
        }
        skippedThisTick.clear();

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.isDead()) continue;

                EntityLimiter.Decision d = limiter.check(entity, world);
                if (d == EntityLimiter.Decision.REMOVE) {
                    entity.remove();
                } else if (d == EntityLimiter.Decision.SKIP) {
                    if (entity instanceof Mob mob) {
                        mob.setAware(false);
                        skippedThisTick.add(entity.getUniqueId());
                    }
                }
            }
        }
    }

    @Override
    public void onDisable() {
        // Restore all skipped entities
        for (java.util.UUID id : skippedThisTick) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof Mob mob) mob.setAware(true);
        }
    }
}
