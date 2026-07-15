package dev.world.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

public final class OreDepositVisuals {
    private OreDepositVisuals() {
    }

    public static Visual forOre(ResourceLocation oreBlockId, BlockState oreState, ServerLevel serverLevel, BlockGetter level, BlockPos pos, BlockState localStoneState) {
        int mapColor = oreState.getMapColor(level, pos).col;
        int base = baseFor(serverLevel, localStoneState, mapColor);
        int oreIndex = OreDepositOrePalette.indexOf(serverLevel, oreState.getBlock());
        return new Visual(base, Math.max(0, oreIndex));
    }

    private static int baseFor(ServerLevel level, BlockState localStoneState, int fallbackColor) {
        int index = OreDepositStonePalette.indexOf(level, localStoneState.getBlock());
        if (index >= 0) {
            return index;
        }

        return OreDepositStonePalette.nearestByMapColor(level, fallbackColor);
    }

    public record Visual(int base, int oreIndex) {
    }
}
