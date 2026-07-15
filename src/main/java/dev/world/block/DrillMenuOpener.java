package dev.world.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

@FunctionalInterface
public interface DrillMenuOpener {
    InteractionResult open(Level level, BlockPos origin, Player player);
}
