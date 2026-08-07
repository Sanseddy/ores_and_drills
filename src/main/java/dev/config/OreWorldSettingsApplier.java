package dev.config;

import dev.OresAndDrillsMod;
import dev.world.level.levelgen.DepositTerrainValidator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Applies ore-generation settings chosen on the "Ore Settings" screen straight into the single global
 * common config. Since the config is common (not per-world), this now applies immediately when the screen
 * is closed, rather than deferring to a per-world config-load event — it affects every world from then on.
 * Per-ore frequency/size/richness is captured by {@code PrimaryLevelDataMixin} and saved inside the new
 * world's {@code level.dat}, rather than being written into the common config.
 */
public final class OreWorldSettingsApplier {
    private OreWorldSettingsApplier() {
    }

    public static void applyOreWorldSettings(Set<ResourceLocation> disabledOres, List<String> overrides) {
        OreDepositConfig.DISABLED_DEPOSIT_ORES.set(encodeDisabled(disabledOres));
        OreOverrides.setWorldOverrides(overrides);
        OreDepositConfig.SPEC.save();
        DepositTerrainValidator.clearCache();

        OresAndDrillsMod.LOGGER.debug("Ore deposits: applied ore settings from the Ore Settings screen");
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
