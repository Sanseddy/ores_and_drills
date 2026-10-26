package dev.world.level.storage;

import dev.OresAndDrillsMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.IOUtilities;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * SavedData whose world-save path costs the server thread only the NBT build.
 *
 * <p>NeoForge's default deep-copies the whole tag on the server thread before handing it to the IO worker,
 * and the IO worker then compresses it at gzip level 6. A flushing save (quit, {@code /save-all flush},
 * server stop) waits for that whole queue. Subclasses here build fresh arrays and tags on every
 * {@link #save(CompoundTag, HolderLookup.Provider)} and never mutate them afterwards, so the copy is
 * redundant; their data is mostly primitive arrays, where the fastest deflate level loses little size.</p>
 *
 * <p>The file is still written through NeoForge's ordered IO queue with its atomic temp-file replace,
 * so flushing saves wait for it and a crash cannot leave a half-written file. Output is ordinary gzip NBT,
 * readable by vanilla and by older builds of this mod.</p>
 */
public abstract class FastSavedData extends SavedData {
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Must return a tag that this instance never mutates or reuses afterwards, because the IO worker
     * serializes it after the server thread has moved on. Shared immutable arrays are allowed.
     */
    @Override
    public abstract CompoundTag save(CompoundTag tag, HolderLookup.Provider registries);

    @Override
    public void save(File file, HolderLookup.Provider registries) {
        if (!isDirty()) {
            return;
        }
        // Cleared before the build: a worldgen thread mutating during it marks the data dirty again
        // instead of having its change silently dropped by a later reset.
        setDirty(false);
        CompoundTag root = new CompoundTag();
        try {
            root.put("data", save(new CompoundTag(), registries));
        } catch (RuntimeException exception) {
            setDirty(true);
            throw exception;
        }
        NbtUtils.addCurrentDataVersion(root);

        Path path = file.toPath();
        IOUtilities.withIOWorker(() -> {
            try {
                IOUtilities.atomicWrite(path, stream -> writeCompressed(root, stream));
            } catch (IOException exception) {
                OresAndDrillsMod.LOGGER.error("Could not save data {}", this, exception);
            }
        });
    }

    static void writeCompressed(CompoundTag tag, OutputStream stream) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new FastGzipOutputStream(stream), BUFFER_SIZE))) {
            NbtIo.write(tag, output);
        }
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        private FastGzipOutputStream(OutputStream stream) throws IOException {
            super(stream, BUFFER_SIZE);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }
}
