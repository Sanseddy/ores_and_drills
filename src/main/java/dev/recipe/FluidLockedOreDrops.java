package dev.recipe;

import net.neoforged.neoforge.event.level.BlockDropsEvent;

/**
 * Ore that needs a mining fluid drops nothing however it is broken: by hand, by other mods' machines (Create
 * fires this event for its drills) or by world destruction. Ores & Drills drills never come through here:
 * they take the recipe result straight from the block.
 */
public final class FluidLockedOreDrops {
    private FluidLockedOreDrops() {}

    public static void onBlockDrops(BlockDropsEvent event) {
        if (DrillMiningRecipes.requiresFluidOutsideDrills(event.getLevel(), event.getState())) {
            event.getDrops().clear();
            event.setDroppedExperience(0);
            event.setCanceled(true);
        }
    }
}
