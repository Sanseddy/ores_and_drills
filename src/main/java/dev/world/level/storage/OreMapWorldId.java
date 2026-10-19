package dev.world.level.storage;

import dev.OresAndDrillsMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

/** Persistent identity used to keep client ore-map files separate after a server world reset. */
public final class OreMapWorldId extends SavedData {
    private static final String FILE_ID = OresAndDrillsMod.MOD_ID + "_ore_map_world_id";
    private static final String TAG_ID = "Id";
    private static final SavedData.Factory<OreMapWorldId> FACTORY =
            new SavedData.Factory<>(OreMapWorldId::create, OreMapWorldId::load);

    private final UUID id;

    private OreMapWorldId(UUID id) {
        this.id = id;
    }

    public static OreMapWorldId get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public UUID id() {
        return id;
    }

    private static OreMapWorldId create() {
        OreMapWorldId data = new OreMapWorldId(UUID.randomUUID());
        data.setDirty();
        return data;
    }

    private static OreMapWorldId load(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            return new OreMapWorldId(UUID.fromString(tag.getString(TAG_ID)));
        } catch (IllegalArgumentException exception) {
            return create();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString(TAG_ID, id.toString());
        return tag;
    }
}
