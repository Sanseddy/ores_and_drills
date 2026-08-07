package dev.network;

import dev.world.inventory.AbstractEnergyDrillMenu;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositStonePalette;

public final class ModNetworking {
    private ModNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("5");
        registrar.playToServer(
                FluidTankClickPayload.TYPE,
                FluidTankClickPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer serverPlayer
                            && serverPlayer.containerMenu.containerId == payload.containerId()
                            && serverPlayer.containerMenu instanceof AbstractEnergyDrillMenu drillMenu) {
                        drillMenu.handleFluidTankClick(serverPlayer);
                    }
                })
        ).playToClient(
                OreDepositPalettePayload.TYPE,
                OreDepositPalettePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    OreDepositStonePalette.applyClientPalette(payload.bases());
                    OreDepositOrePalette.applyClientPalette(payload.ores(), payload.drops());
                })
        ).playToClient(
                OreDepositRemainingPayload.TYPE,
                OreDepositRemainingPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OreDepositData.applyClientEntry(
                        context.player().level(),
                        payload.pos(),
                        payload.baseIndex(),
                        payload.oreIndex(),
                        payload.remainingOre(),
                        payload.initialOre(),
                        payload.initialRichness(),
                        payload.richnessReferenceAmount(),
                        payload.tier()
                ))
        );
    }
}
