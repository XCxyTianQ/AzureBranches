package com.azurebranches.storage;

import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AzureBranches EXP7: region-format selector (STM: config-driven, default MCA).
 *
 * <p>{@link #create(RegionStorageInfo, Path, Path, boolean)} is the single
 * creation point for Moonrise's chunk storage after the EXP7 wiring — a
 * {@code RegionFile} (vanilla MCA) or a {@link BufferedLinearRegionFile}
 * (v4 layout, see STORAGE-V4-SPEC.md).</p>
 */
public enum RegionFormat {

    /** Vanilla anvil region files (via the public-surface adapter). */
    MCA("mca") {
        @Override
        public IRegionFile create(final RegionStorageInfo info, final Path filePath, final Path folder, final boolean sync) throws IOException {
            return new net.minecraft.world.level.chunk.storage.RegionFileAdapter(info, filePath, folder, sync);
        }
    },

    /** Buffered Linear v4 (AzureBranches layout; read-compatible with v0x02/0x03 + legacy linear). */
    B_LINEAR_V4("b_linear_v4") {
        @Override
        public IRegionFile create(final RegionStorageInfo info, final Path filePath, final Path folder, final boolean sync) throws IOException {
            // flusher is process-global: one checker + one IO pool shared by all region files
            final BufferedLinearRegionFileFlusher flusher = RegionFormat.flusher();
            return new BufferedLinearRegionFile(filePath, RegionFormat.compressionLevel(), flusher);
        }
    };

    private final String configName;

    RegionFormat(final String configName) {
        this.configName = configName;
    }

    public String configName() {
        return this.configName;
    }

    public abstract IRegionFile create(RegionStorageInfo info, Path filePath, Path folder, boolean sync) throws IOException;

    /** Static entry: create a region-file backend of the currently selected format. */
    public static IRegionFile open(final RegionStorageInfo info, final Path filePath, final Path folder, final boolean sync) throws IOException {
        // EXP7: apply storage.region_format from the global config before the
        // first region file is created (idempotent; retries while uninitialized).
        RegionFormatBootstrap.ensureApplied();
        return RegionFormat.current().create(info, filePath, folder, sync);
    }

    /** Current format selection (default: vanilla MCA). */
    private static volatile RegionFormat current = RegionFormat.MCA;

    /** Compression level used by the linear backends (1..22, default 1). */
    private static volatile int compressionLevel = 1;

    private static final AtomicReference<BufferedLinearRegionFileFlusher> FLUSHER = new AtomicReference<>();

    public static RegionFormat current() {
        return current;
    }

    /** Called by config loading (AzureBranchesConfig storage.region_format). */
    public static void setCurrent(final RegionFormat format) {
        if (format != null) {
            current = format;
        }
    }

    public static void setCompressionLevel(final int level) {
        if (level >= 1 && level <= 22) {
            compressionLevel = level;
        }
    }

    public static int compressionLevel() {
        return compressionLevel;
    }

    private static BufferedLinearRegionFileFlusher flusher() {
        final BufferedLinearRegionFileFlusher existing = FLUSHER.get();
        if (existing != null) {
            return existing;
        }
        final BufferedLinearRegionFileFlusher created =
            new BufferedLinearRegionFileFlusher(4, 20, 3000);
        if (FLUSHER.compareAndSet(null, created)) {
            Runtime.getRuntime().addShutdownHook(new Thread(created::shutdown,
                "AzureBranches Linear Flusher Shutdown"));
            return created;
        }
        return FLUSHER.get();
    }
}
