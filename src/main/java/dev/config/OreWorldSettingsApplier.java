package dev.config;

import dev.OresAndDrillsMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Holds the ore-generation settings chosen on the "Ore Settings" screen for the world being created:
 * disabled ores, per-ore frequency/size/richness, ore/dimension exclusions and the vanilla-like mode.
 * {@code PrimaryLevelDataMixin} captures them into that world's {@code level.dat}; nothing is written to
 * the common config, so they never leak into other worlds.
 */
public final class OreWorldSettingsApplier {
    private OreWorldSettingsApplier() {
    }

    public static void applyOreWorldSettings(Set<ResourceLocation> disabledOres, List<String> overrides,
                                             List<String> dimensionOverrides, boolean vanillaLikeGeneration) {
        OreWorldDisabledOres.set(encodeDisabled(disabledOres));
        OreOverrides.setWorldOverrides(overrides);
        OreDimensionOverrides.setDisabled(dimensionOverrides);
        OreGenerationMode.setVanillaLikeGeneration(vanillaLikeGeneration);

        OresAndDrillsMod.LOGGER.debug("Ore deposits: applied ore settings from the Ore Settings screen");
    }

    /**
     * Starts a new world from default ore settings. The values above stay in memory after a world is created
     * or opened, so without this reset the next new world would silently inherit them.
     */
    public static void resetForNewWorld() {
        applyOreWorldSettings(Set.of(), List.of(), List.of(), false);
    }

    /** Keeps the active datapack preset selection in sync whenever the common config (re)loads. */
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getType() != ModConfig.Type.COMMON || event.getConfig().getSpec() != OreDepositConfig.SPEC) {
            return;
        }

        OreSettingsPresetManager.refreshActivePreset();
    }

    private static List<String> encodeDisabled(Set<ResourceLocation> disabledOres) {
        List<String> encoded = new ArrayList<>();
        for (ResourceLocation oreId : disabledOres) {
            if (BuiltInRegistries.BLOCK.containsKey(oreId)) {
                encoded.add(oreId.toString());
            }
        }
        return encoded;
    }
}
