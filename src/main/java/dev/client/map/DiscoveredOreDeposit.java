package dev.client.map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record DiscoveredOreDeposit(
        long id,
        ResourceLocation dimension,
        BlockPos center,
        ResourceLocation oreId,
        int tier,
        int color,
        long remainingOre,
        long initialOre,
        List<OreMapCell> cells
) {
    public DiscoveredOreDeposit {
        center = center.immutable();
        cells = List.copyOf(cells);
    }

    public record OreMapCell(int offsetX, int offsetZ, int remainingOre, int initialOre) {
    }
}
