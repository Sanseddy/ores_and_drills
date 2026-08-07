package dev.registry;

import dev.OresAndDrillsMod;
import dev.world.level.levelgen.OreDepositChunkData;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.common.util.FriendlyByteBufUtil;
import net.neoforged.neoforge.network.payload.SyncAttachmentsPayload;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;

public final class ModAttachments {
    private static final String TAG_KEYS = "Keys";
    private static final String TAG_VALUES = "Values";
    private static final String TAG_RICHNESS_REFERENCES = "RichnessReferences";
    private static final StreamCodec<RegistryFriendlyByteBuf, OreDepositChunkData> ORE_DEPOSIT_SYNC_CODEC = new StreamCodec<>() {
        @Override
        public OreDepositChunkData decode(RegistryFriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            int[] keys = new int[size];
            long[] values = new long[size];
            for (int index = 0; index < size; index++) {
                keys[index] = buffer.readVarInt();
                values[index] = buffer.readLong();
            }

            OreDepositChunkData data = new OreDepositChunkData();
            data.load(keys, values);
            return data;
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, OreDepositChunkData data) {
            encodeSnapshot(buffer, data.snapshot());
        }
    };

    private static void encodeSnapshot(
            RegistryFriendlyByteBuf buffer,
            OreDepositChunkData.Snapshot snapshot
    ) {
        int[] keys = snapshot.keys();
        long[] values = snapshot.values();
        int size = Math.min(keys.length, values.length);
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            buffer.writeVarInt(keys[index]);
            buffer.writeLong(values[index]);
        }
    }

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(
            NeoForgeRegistries.Keys.ATTACHMENT_TYPES,
            OresAndDrillsMod.MOD_ID
    );

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<OreDepositChunkData>> ORE_DEPOSITS =
            ATTACHMENTS.register("ore_deposits", () -> AttachmentType.builder(OreDepositChunkData::new)
                    .serialize(new IAttachmentSerializer<CompoundTag, OreDepositChunkData>() {
                        @Override
                        public OreDepositChunkData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
                            OreDepositChunkData data = new OreDepositChunkData();
                            data.load(tag.getIntArray(TAG_KEYS), tag.getLongArray(TAG_VALUES), tag.getIntArray(TAG_RICHNESS_REFERENCES));
                            return data;
                        }

                        @Nullable
                        @Override
                        public CompoundTag write(OreDepositChunkData data, HolderLookup.Provider provider) {
                            OreDepositChunkData.Snapshot snapshot = data.snapshot();
                            if (snapshot.keys().length == 0) {
                                return null;
                            }

                            CompoundTag tag = new CompoundTag();
                            tag.putIntArray(TAG_KEYS, snapshot.keys());
                            tag.putLongArray(TAG_VALUES, snapshot.values());
                            return tag;
                        }
                    })
                    .sync(ORE_DEPOSIT_SYNC_CODEC)
                    .build());

    /**
     * Builds the initial attachment packet for a chunk sender that bypasses NeoForge's
     * {@code ChunkWatchEvent.Sent}. The returned packet must be queued immediately after that sender's
     * chunk packet so the client has a chunk to attach the data to.
     */
    @Nullable
    public static Packet<? super ClientGamePacketListener> initialOreDepositSyncPacket(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel serverLevel) {
            OreDepositData.restorePhysicalChunkEntries(serverLevel, chunk);
        }
        OreDepositChunkData data = chunk.getExistingDataOrNull(ORE_DEPOSITS);
        if (data == null) {
            return null;
        }
        OreDepositChunkData.Snapshot snapshot = data.snapshot();
        if (snapshot.keys().length == 0) {
            return null;
        }

        byte[] payload = FriendlyByteBufUtil.writeCustomData(buffer -> {
            buffer.writeBoolean(true);
            encodeSnapshot(buffer, snapshot);
        }, chunk.getLevel().registryAccess());
        return new SyncAttachmentsPayload(
                new SyncAttachmentsPayload.ChunkTarget(chunk.getPos()),
                java.util.List.of(ORE_DEPOSITS.get()),
                payload
        ).toVanillaClientbound();
    }

    private ModAttachments() {
    }
}
