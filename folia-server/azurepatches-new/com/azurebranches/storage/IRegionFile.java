package com.azurebranches.storage;

import ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemRegionFile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * AzureBranches EXP7: configurable region-file backend.
 *
 * <p>Plugs into Moonrise's chunk storage in place of the vanilla
 * {@link net.minecraft.world.level.chunk.storage.RegionFile}. Implementations:
 * {@link RegionFormat#MCA} (vanilla RegionFile) and {@link RegionFormat#B_LINEAR_V4}
 * ({@link BufferedLinearRegionFile} — see STORAGE-V4-SPEC.md).</p>
 *
 * <p>Derived from the Luminol/Arbor {@code IRegionFile} abstraction
 * (author MrHua269 / Little / the xymb lineage), GPLv3. See NOTICE.md.</p>
 */
public interface IRegionFile extends ChunkSystemRegionFile, AutoCloseable {
    Path getPath();

    DataInputStream getChunkDataInputStream(ChunkPos pos) throws IOException;

    boolean doesChunkExist(ChunkPos pos) throws Exception;

    DataOutputStream getChunkDataOutputStream(ChunkPos pos) throws IOException;

    void flush() throws IOException;

    void clear(ChunkPos pos) throws IOException;

    boolean hasChunk(ChunkPos pos);

    void close() throws IOException;

    void write(ChunkPos pos, ByteBuffer buf) throws IOException;

    CompoundTag getOversizedData(int x, int z) throws IOException;

    boolean isOversized(int x, int z);

    boolean recalculateHeader() throws IOException;

    void setOversized(int x, int z, boolean oversized) throws IOException;

    default int getRecalculateCount() {
        return 0;
    }
}
