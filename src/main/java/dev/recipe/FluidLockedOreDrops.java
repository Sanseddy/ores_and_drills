package dev.recipe;

import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

public final class FluidLockedOreDrops {
    private FluidLockedOreDrops() {}

    public static void onBlockDrops(BlockDropsEvent event) {
        if (event.getBreaker() instanceof Player
                && DrillMiningRecipes.requiresFluidForPlayer(event.getLevel(), event.getState())) {
            event.getDrops().clear();
            event.setDroppedExperience(0);
            event.setCanceled(true);
        }
    }
}
