package com.azurebranches.storage;

import com.azurebranches.config.AzureBranchesConfig;

/**
 * AzureBranches EXP7: applies {@code storage.region_format} from the
 * AzureBranches global configuration to {@link RegionFormat}.
 *
 * <p>Lives on the Folia side on purpose: it reads the (common-module)
 * {@link AzureBranchesConfig} through an already-initialized instance and must
 * only run once the plugin's {@code onEnable} has loaded the TOML. The single
 * call site is {@link RegionFormat#open} — the one place every region file is
 * created — so a region file is never created with a stale formats selection.
 * If the config is not yet initialized (e.g. during the initial FileFixerUpper
 * run) the selection is retried at the next attempt instead of being lost.</p>
 */
public final class RegionFormatBootstrap {

    private static volatile boolean applied;

    private RegionFormatBootstrap() {
    }

    /** Applies the configured format once; safe to call from any thread, any number of times. */
    public static void ensureApplied() {
        if (applied) {
            return;
        }
        synchronized (RegionFormatBootstrap.class) {
            if (applied) {
                return;
            }
            final AzureBranchesConfig config = AzureBranchesConfig.getOrNull();
            if (config == null) {
                return; // config not loaded yet — retry at the next region-file open
            }
            final String raw = config.storageRegionFormat();
            try {
                final RegionFormat format = RegionFormat.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
                RegionFormat.setCurrent(format);
                RegionFormat.setCompressionLevel(config.storageCompressionLevel());
                if (format == RegionFormat.B_LINEAR_V4) {
                    System.out.println("[AzureBranches] storage.region_format=b_linear_v4 (compression "
                        + config.storageCompressionLevel() + ")");
                }
                applied = true;
            } catch (final IllegalArgumentException e) {
                applied = true; // bad config value: log once, keep the mca default
                System.out.println("[AzureBranches] Unknown storage.region_format '" + raw + "', keeping mca");
            }
        }
    }
}
