package dev.network;

import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreDepositPaletteData;
import dev.world.level.levelgen.OreDepositStonePalette;
import dev.world.level.levelgen.OreTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public final class OreDepositPaletteSync {
    private OreDepositPaletteSync() {
    }

    public static void onDatapackSync(OnDatapackSyncEvent event) {
        OreTags.clearCache();
        OreDepositStonePalette.clearAvailableCache();
        OreDepositOrePalette.clearAvailableCache();
        ServerLevel level = event.getPlayerList().getServer().overworld();
        OreDepositPaletteData data = OreDepositPaletteData.get(level);
        data.refreshFromRegistries();
        List<ResourceLocation> ores = data.ores();
        OreDepositPalettePayload payload = new OreDepositPalettePayload(data.bases(), ores, dropIds(level, ores));
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    private static List<ResourceLocation> dropIds(ServerLevel level, List<ResourceLocation> oreIds) {
        List<ResourceLocation> drops = new ArrayList<>(oreIds.size());
        for (ResourceLocation oreId : oreIds) {
            drops.add(dropId(level, oreId));
        }
        return List.copyOf(drops);
    }

    private static ResourceLocation dropId(ServerLevel level, ResourceLocation oreId) {
        Block block = BuiltInRegistries.BLOCK.get(oreId);
        if (block == null) {
            return BuiltInRegistries.ITEM.getKey(Items.AIR);
        }

        for (ItemStack stack : Block.getDrops(block.defaultBlockState(), level, BlockPos.ZERO, null)) {
            if (!stack.isEmpty()) {
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (itemId != null) {
                    return itemId;
                }
            }
        }

        ResourceLocation fallback = BuiltInRegistries.ITEM.getKey(block.asItem());
        return fallback != null ? fallback : BuiltInRegistries.ITEM.getKey(Items.AIR);
    }
}
