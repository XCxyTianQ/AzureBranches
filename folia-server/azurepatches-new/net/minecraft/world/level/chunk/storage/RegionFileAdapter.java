package net.minecraft.world.level.chunk.storage;

import com.azurebranches.storage.IRegionFile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * AzureBranches EXP7: vanilla MCA backend adapter.
 *
 * <p>Vanilla {@link RegionFile} implements {@code ChunkSystemRegionFile} but
 * several of its methods ({@code write}, {@code getOversizedData},
 * {@code isOversized}, {@code setOversized}, {@code recalculateHeader}) are
 * {@code protected}/package-private and therefore cannot satisfy
 * {@link IRegionFile} from another package. Living in the same package lets the
 * adapter widen them to {@code public} while delegating to the vanilla
 * implementation — the whole MCA path stays vanilla-identical.</p>
 */
public final class RegionFileAdapter extends RegionFile implements IRegionFile {

    public RegionFileAdapter(final RegionStorageInfo info, final Path filePath, final Path folder, final boolean sync) throws IOException {
        super(info, filePath, folder, sync);
    }

    @Override
    public synchronized void write(final ChunkPos pos, final ByteBuffer data) throws IOException {
        super.write(pos, data);
    }

    @Override
    public synchronized CompoundTag getOversizedData(final int x, final int z) throws IOException {
        return super.getOversizedData(x, z);
    }

    @Override
    public synchronized boolean isOversized(final int x, final int z) {
        return super.isOversized(x, z);
    }

    @Override
    public synchronized void setOversized(final int x, final int z, final boolean oversized) throws IOException {
        super.setOversized(x, z, oversized);
    }

    @Override
    public boolean recalculateHeader() throws IOException {
        return super.recalculateHeader();
    }
}
