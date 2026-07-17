package dev.mixin;

import dev.registry.ModAttachments;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Sable sends plot/sub-level chunks through its own sender instead of vanilla's chunk-watch path.
 * NeoForge consequently never receives {@code ChunkWatchEvent.Sent} for that packet and cannot attach
 * the initial synchronized chunk data. Add our attachment packet to Sable's own packet consumer so it
 * stays immediately after the chunk even when Sable is assembling a bundle before sending it.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.plot.SubLevelPlayerChunkSender", remap = false)
public abstract class SableSubLevelPlayerChunkSenderMixin {
    @Inject(method = "sendChunk", at = @At("RETURN"), require = 0, remap = false)
    private static void oresAndDrills$syncOreDepositAttachment(
            Consumer<Packet<? super ClientGamePacketListener>> packetConsumer,
            LevelLightEngine lightEngine,
            LevelChunk chunk,
            CallbackInfo callbackInfo
    ) {
        Packet<? super ClientGamePacketListener> attachmentPacket =
                ModAttachments.initialOreDepositSyncPacket(chunk);
        if (attachmentPacket != null) {
            packetConsumer.accept(attachmentPacket);
        }
    }
}
