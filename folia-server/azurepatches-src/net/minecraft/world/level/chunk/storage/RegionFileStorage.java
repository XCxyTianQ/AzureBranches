package net.minecraft.world.level.chunk.storage;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.util.ExceptionCollector;
import net.minecraft.util.FileUtil;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

public class RegionFileStorage implements AutoCloseable, ca.spottedleaf.moonrise.patches.chunk_system.io.ChunkSystemRegionFileStorage { // Paper - rewrite chunk system
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger(); // Paper
    public static final String ANVIL_EXTENSION = ".mca";
    private static final int MAX_CACHE_SIZE = 256;
    public final Long2ObjectLinkedOpenHashMap<com.azurebranches.storage.IRegionFile> regionCache = new Long2ObjectLinkedOpenHashMap<>();
    private final RegionStorageInfo info;
    private final Path folder;
    private final boolean sync;

    // Paper start - recalculate region file headers
    private final boolean isChunkData;

    @Nullable
    public static ChunkPos getRegionFileCoordinates(Path file) {
        String fileName = file.getFileName().toString();
        if (!fileName.startsWith("r.") || !fileName.endsWith(".mca")) {
            return null;
        }

        String[] split = fileName.split("\\.");

        if (split.length != 4) {
            return null;
        }

        try {
            int x = Integer.parseInt(split[1]);
            int z = Integer.parseInt(split[2]);

            return new ChunkPos(x << 5, z << 5);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    // Paper end
    // Paper start - rewrite chunk system
    private static final int REGION_SHIFT = 5;
    private static final int MAX_NON_EXISTING_CACHE = 1024 * 4;
    private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet nonExistingRegionFiles = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    private static String getRegionFileName(final int chunkX, final int chunkZ) {
        return "r." + (chunkX >> REGION_SHIFT) + "." + (chunkZ >> REGION_SHIFT) + ".mca";
    }

    private boolean doesRegionFilePossiblyExist(final long position) {
        synchronized (this.nonExistingRegionFiles) {
            if (this.nonExistingRegionFiles.contains(position)) {
                this.nonExistingRegionFiles.addAndMoveToFirst(position);
                return false;
            }
            return true;
        }
    }

    private void createRegionFile(final long position) {
        synchronized (this.nonExistingRegionFiles) {
            this.nonExistingRegionFiles.remove(position);
        }
    }

    private void markNonExisting(final long position) {
        synchronized (this.nonExistingRegionFiles) {
            if (this.nonExistingRegionFiles.addAndMoveToFirst(position)) {
                while (this.nonExistingRegionFiles.size() >= MAX_NON_EXISTING_CACHE) {
                    this.nonExistingRegionFiles.removeLastLong();
                }
            }
        }
    }

    @Override
    public final boolean moonrise$doesRegionFileNotExistNoIO(final int chunkX, final int chunkZ) {
        return !this.doesRegionFilePossiblyExist(ChunkPos.pack(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT));
    }

    @Override
    public synchronized final com.azurebranches.storage.IRegionFile moonrise$getRegionFileIfLoaded(final int chunkX, final int chunkZ) {
        return this.regionCache.getAndMoveToFirst(ChunkPos.pack(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT));
    }

    @Override
    public synchronized final com.azurebranches.storage.IRegionFile moonrise$getRegionFileIfExists(final int chunkX, final int chunkZ) throws IOException {
        final long key = ChunkPos.pack(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);

        com.azurebranches.storage.IRegionFile ret = this.regionCache.getAndMoveToFirst(key);
        if (ret != null) {
            return ret;
        }

        if (!this.doesRegionFilePossiblyExist(key)) {
            return null;
        }

        final int cacheSize = io.papermc.paper.configuration.GlobalConfiguration.get() == null ? 256 : io.papermc.paper.configuration.GlobalConfiguration.get().misc.regionFileCacheSize; // Paper - Sanitise RegionFileCache and make configurable - Config not available during initial FileFixerUpper run

        if (this.regionCache.size() >= cacheSize) {
            this.regionCache.removeLast().close();
        }

        final Path regionPath = this.folder.resolve(getRegionFileName(chunkX, chunkZ));

        if (!java.nio.file.Files.exists(regionPath)) {
            this.markNonExisting(key);
            return null;
        }

        this.createRegionFile(key);

        FileUtil.createDirectoriesSafe(this.folder);

        ret = com.azurebranches.storage.RegionFormat.open(this.info, regionPath, this.folder, this.sync);

        this.regionCache.putAndMoveToFirst(key, ret);

        return ret;
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(
        final int chunkX, final int chunkZ, final CompoundTag compound
    ) throws IOException {
        if (compound == null) {
            return new ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData(
                compound, ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.DELETE,
                null, null
            );
        }

        final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        final com.azurebranches.storage.IRegionFile regionFile = this.getRegionFile(pos);

        // note: not required to keep com.azurebranches.storage.IRegionFile loaded after this call, as the write param takes a com.azurebranches.storage.IRegionFile as input
        // (and, the com.azurebranches.storage.IRegionFile parameter is unused for writing until the write call)
        final ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData writeData = ((ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemRegionFile)regionFile).moonrise$startWrite(compound, pos);

        try { // Paper - implement RegionFileSizeException
        try {
            NbtIo.write(compound, writeData.output());
        } finally {
            writeData.output().close();
        }
        // Paper start - implement RegionFileSizeException
        } catch (final RegionFileSizeException ex) {
            // note: it's OK if close() is called, as close() here will not issue a write to the com.azurebranches.storage.IRegionFile
            // see startWrite
            final int maxSize = RegionFile.MAX_CHUNK_SIZE / (1024 * 1024);
            LOGGER.error("Chunk at (" + chunkX + "," + chunkZ + ") in regionfile '" + regionFile.getPath().toString() + "' exceeds max size of " + maxSize + "MiB, it has been deleted from disk.");
            return new ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData(
                compound, ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.DELETE,
                null, null
            );
        }
        // Paper end - implement RegionFileSizeException

        return writeData;
    }

    @Override
    public final void moonrise$finishWrite(
        final int chunkX, final int chunkZ, final ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData writeData
    ) throws IOException {
        final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        if (writeData.result() == ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.DELETE) {
            final com.azurebranches.storage.IRegionFile regionFile = this.moonrise$getRegionFileIfExists(chunkX, chunkZ);
            if (regionFile != null) {
                regionFile.clear(pos);
            } // else: didn't exist

            return;
        }

        writeData.write().run(this.getRegionFile(pos));
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData moonrise$readData(
        final int chunkX, final int chunkZ
    ) throws IOException {
        final com.azurebranches.storage.IRegionFile regionFile = this.moonrise$getRegionFileIfExists(chunkX, chunkZ);

        final DataInputStream input = regionFile == null ? null : regionFile.getChunkDataInputStream(new ChunkPos(chunkX, chunkZ));

        if (input == null) {
            return new ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData(
                ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.NO_DATA, null, null, regionFile == null ? 0 : regionFile.getRecalculateCount() // Paper - Attempt to recalculate com.azurebranches.storage.IRegionFile header if it is corrupt
            );
        }

        final ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData ret = new ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData(
            ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.HAS_DATA, input, null, regionFile.getRecalculateCount() // Paper - Attempt to recalculate com.azurebranches.storage.IRegionFile header if it is corrupt
        );

        if (!(input instanceof ca.spottedleaf.moonrise.patches.chunk_system.util.stream.ExternalChunkStreamMarker)) {
            // internal stream, which is fully read
            return ret;
        }

        final CompoundTag syncRead = this.moonrise$finishRead(chunkX, chunkZ, ret);

        if (syncRead == null) {
            // need to try again
            return this.moonrise$readData(chunkX, chunkZ);
        }

        return new ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData(
            ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.SYNC_READ, null, syncRead, regionFile.getRecalculateCount() // Paper - Attempt to recalculate com.azurebranches.storage.IRegionFile header if it is corrupt
        );
    }

    // if the return value is null, then the caller needs to re-try with a new call to readData()
    @Override
    public final CompoundTag moonrise$finishRead(
        final int chunkX, final int chunkZ, final ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData readData
    ) throws IOException {
        try {
            // Paper start - Attempt to recalculate com.azurebranches.storage.IRegionFile header if it is corrupt
            final CompoundTag ret = NbtIo.read(readData.input());
            if (!this.isChunkData) {
                return ret;
            }

            final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            final ChunkPos headerChunkPos = SerializableChunkData.getChunkCoordinate(ret);
            final com.azurebranches.storage.IRegionFile regionFile = this.getRegionFile(pos);

            if (regionFile.getRecalculateCount() != readData.recalculateCount()) {
                return null;
            }

            if (!headerChunkPos.equals(pos)) {
                LOGGER.error("Attempting to read chunk data at " + pos + " but got chunk data for " + headerChunkPos + " instead! Attempting com.azurebranches.storage.IRegionFile recalculation " + regionFile.getPath().toAbsolutePath());
                if (regionFile.recalculateHeader()) {
                    return null;
                }

                LOGGER.error(com.mojang.logging.LogUtils.FATAL_MARKER, "Can't recalculate com.azurebranches.storage.IRegionFile header?");
                return ret;
            }

            return ret;
            // Paper end - Attempt to recalculate com.azurebranches.storage.IRegionFile header if it is corrupt
        } finally {
            readData.input().close();
        }
    }
    // Paper end - rewrite chunk system
    // Paper start - rewrite chunk system
    public com.azurebranches.storage.IRegionFile getRegionFile(ChunkPos chunkcoordintpair) throws IOException {
        return this.getRegionFile(chunkcoordintpair, false);
    }
    // Paper end - rewrite chunk system

    protected RegionFileStorage(final RegionStorageInfo info, final Path folder, final boolean sync) { // Paper - protected
        this.folder = folder;
        this.sync = sync;
        this.info = info;
        this.isChunkData = info.dfuType()[0] == net.minecraft.util.datafix.DataFixTypes.CHUNK; // Paper - recalculate region file headers
    }

    @org.jetbrains.annotations.Contract("_, false -> !null") private com.azurebranches.storage.IRegionFile getRegionFile(final ChunkPos pos, boolean existingOnly) throws IOException { // CraftBukkit
        // Paper start - rewrite chunk system
        if (existingOnly) {
            return this.moonrise$getRegionFileIfExists(pos.x(), pos.z());
        }
        synchronized (this) {
            final long key = ChunkPos.pack(pos.x() >> REGION_SHIFT, pos.z() >> REGION_SHIFT);

            com.azurebranches.storage.IRegionFile ret = this.regionCache.getAndMoveToFirst(key);
            if (ret != null) {
                return ret;
            }

            final int cacheSize = io.papermc.paper.configuration.GlobalConfiguration.get() == null ? 256 : io.papermc.paper.configuration.GlobalConfiguration.get().misc.regionFileCacheSize; // Paper - Sanitise RegionFileCache and make configurable - Config not available during initial FileFixerUpper run

            if (this.regionCache.size() >= cacheSize) {
                this.regionCache.removeLast().close();
            }

            final Path regionPath = this.folder.resolve(getRegionFileName(pos.x(), pos.z()));

            this.createRegionFile(key);

            FileUtil.createDirectoriesSafe(this.folder);

            ret = com.azurebranches.storage.RegionFormat.open(this.info, regionPath, this.folder, this.sync);

            this.regionCache.putAndMoveToFirst(key, ret);

            return ret;
        }
        // Paper end - rewrite chunk system
    }

    // Paper start
    private static void printOversizedLog(String msg, Path file, int x, int z) {
        org.apache.logging.log4j.LogManager.getLogger().fatal(msg + " (" + file.toString().replaceAll(".+[\\\\/]", "") + " - " + x + "," + z + ") Go clean it up to remove this message. /minecraft:tp " + (x<<4)+" 128 "+(z<<4) + " - DO NOT REPORT THIS TO PAPER - You may ask for help on Discord, but do not file an issue. These error messages can not be removed.");
    }

    private static CompoundTag readOversizedChunk(com.azurebranches.storage.IRegionFile regionFile, ChunkPos chunkCoordinate) throws IOException {
        synchronized (regionFile) {
            try (DataInputStream datainputstream = regionFile.getChunkDataInputStream(chunkCoordinate)) {
                CompoundTag oversizedData = regionFile.getOversizedData(chunkCoordinate.x(), chunkCoordinate.z());
                CompoundTag chunk = NbtIo.read(datainputstream);
                if (oversizedData == null) {
                    return chunk;
                }
                CompoundTag oversizedLevel = oversizedData.getCompoundOrEmpty("Level");

                mergeChunkList(chunk.getCompoundOrEmpty("Level"), oversizedLevel, "Entities", "Entities");
                mergeChunkList(chunk.getCompoundOrEmpty("Level"), oversizedLevel, "TileEntities", "TileEntities");

                return chunk;
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                throw throwable;
            }
        }
    }

    private static void mergeChunkList(CompoundTag level, CompoundTag oversizedLevel, String key, String oversizedKey) {
        net.minecraft.nbt.ListTag levelList = level.getListOrEmpty(key);
        net.minecraft.nbt.ListTag oversizedList = oversizedLevel.getListOrEmpty(oversizedKey);

        if (!oversizedList.isEmpty()) {
            levelList.addAll(oversizedList);
            level.put(key, levelList);
        }
    }
    // Paper end

    public @Nullable CompoundTag read(final ChunkPos pos) throws IOException {
        // CraftBukkit start - SPIGOT-5680: There's no good reason to preemptively create files on read, save that for writing
        com.azurebranches.storage.IRegionFile region = this.getRegionFile(pos, true);
        if (region == null) {
            return null;
        }
        // CraftBukkit end
        // Paper start
        if (region.isOversized(pos.x(), pos.z())) {
            printOversizedLog("Loading Oversized Chunk!", region.getPath(), pos.x(), pos.z());
            return readOversizedChunk(region, pos);
        }
        // Paper end

        CompoundTag var4;
        try (DataInputStream regionChunkInputStream = region.getChunkDataInputStream(pos)) {
            if (regionChunkInputStream == null) {
                return null;
            }

            var4 = NbtIo.read(regionChunkInputStream);
            // Paper start - recover from corrupt com.azurebranches.storage.IRegionFile header
            if (this.isChunkData) {
                ChunkPos headerChunkPos = SerializableChunkData.getChunkCoordinate(var4);
                if (!headerChunkPos.equals(pos)) {
                    net.minecraft.server.MinecraftServer.LOGGER.error("Attempting to read chunk data at " + pos + " but got chunk data for " + headerChunkPos + " instead! Attempting com.azurebranches.storage.IRegionFile recalculation for com.azurebranches.storage.IRegionFile " + region.getPath().toAbsolutePath());
                    if (region.recalculateHeader()) {
                        return this.read(pos);
                    }
                    net.minecraft.server.MinecraftServer.LOGGER.error("Can't recalculate com.azurebranches.storage.IRegionFile header, regenerating chunk " + pos + " for " + region.getPath().toAbsolutePath());
                    return null;
                }
            }
            // Paper end - recover from corrupt com.azurebranches.storage.IRegionFile header
        }

        return var4;
    }

    public void scanChunk(final ChunkPos pos, final StreamTagVisitor scanner) throws IOException {
        // CraftBukkit start - SPIGOT-5680: There's no good reason to preemptively create files on read, save that for writing
        com.azurebranches.storage.IRegionFile region = this.getRegionFile(pos, true);
        if (region == null) {
            return;
        }
        // CraftBukkit end

        try (DataInputStream regionChunkInputStream = region.getChunkDataInputStream(pos)) {
            if (regionChunkInputStream != null) {
                NbtIo.parse(regionChunkInputStream, scanner, NbtAccounter.unlimitedHeap());
            }
        }
    }

    public void write(final ChunkPos pos, final @Nullable CompoundTag value) throws IOException { // Paper - rewrite chunk system - public
        if (!SharedConstants.DEBUG_DONT_SAVE_WORLD) {
            com.azurebranches.storage.IRegionFile region = this.getRegionFile(pos, value == null); // CraftBukkit // Paper - rewrite chunk system
            // Paper start - rewrite chunk system
            if (region == null) {
                // if the com.azurebranches.storage.IRegionFile doesn't exist, no point in deleting from it
                return;
            }
            // Paper end - rewrite chunk system
            if (value == null) {
                region.clear(pos);
            } else {
                // Paper - Only write if successful
                DataOutputStream output = region.getChunkDataOutputStream(pos);
                try { // Paper - Only write if successful
                    NbtIo.write(value, output);
                    region.setOversized(pos.x(), pos.z(), false); // Paper - We don't do this anymore, mojang stores differently, but clear old meta flag if it exists to get rid of our own meta file once last oversized is gone
                    // Paper start - don't write garbage data to disk if writing serialization fails
                    output.close();
                } catch (final RegionFileSizeException ex) {
                    region.clear(pos);
                    final int maxSize = RegionFile.MAX_CHUNK_SIZE / (1024 * 1024);
                    LOGGER.error("Chunk at (" + pos.x() + "," + pos.z() + ") in regionfile '" + region.getPath().toString() + "' exceeds max size of " + maxSize + "MiB, it has been deleted from disk.");
                    // Paper end - don't write garbage data to disk if writing serialization fails
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        // Paper start - rewrite chunk system
        synchronized (this) {
            final ExceptionCollector<IOException> exceptionCollector = new ExceptionCollector<>();
            for (final com.azurebranches.storage.IRegionFile regionFile : this.regionCache.values()) {
                try {
                    regionFile.close();
                } catch (final IOException ex) {
                    exceptionCollector.add(ex);
                }
            }
            exceptionCollector.throwIfPresent();
        }
        // Paper end - rewrite chunk system
    }

    public void flush() throws IOException {
        // Paper start - rewrite chunk system
        synchronized (this) {
            final ExceptionCollector<IOException> exceptionCollector = new ExceptionCollector<>();
            for (final com.azurebranches.storage.IRegionFile regionFile : this.regionCache.values()) {
                try {
                    regionFile.flush();
                } catch (final IOException ex) {
                    exceptionCollector.add(ex);
                }
            }

            exceptionCollector.throwIfPresent();
        }
        // Paper end - rewrite chunk system
    }

    public RegionStorageInfo info() {
        return this.info;
    }

    // Paper start - don't write garbage data to disk if writing serialization fails
    public static final class RegionFileSizeException extends RuntimeException {

        public RegionFileSizeException(final String message) {
            super(message);
        }
    }
    // Paper end - don't write garbage data to disk if writing serialization fails
}
