package dev.config;

import dev.OresAndDrillsMod;
import dev.world.level.levelgen.DepositTerrainValidator;
import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreDepositStonePalette;
import dev.world.level.levelgen.OreTags;
import dev.world.level.levelgen.OreUnifier;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * Applies the common config right away. Most values are read live, but ore unification, ore/stone palettes,
 * terrain validation and the active preset cache what they derive from it; those are rebuilt here so a
 * change from the settings screens (or a hand-edited file) takes effect without a restart.
 */
public final class ConfigReloader {
    private ConfigReloader() {
    }

    public static void reload() {
        OreTags.clearCache();
        OreUnifier.clearCache();
        OreDepositStonePalette.clearAvailableCache();
        OreDepositOrePalette.clearAvailableCache();
        DepositTerrainValidator.clearCache();
        OreSettingsPresetManager.refreshActivePreset();
        OresAndDrillsMod.LOGGER.debug("Ores & Drills: config reloaded");
    }

    /** The file watcher fires this after the config file changes on disk, including our own saves. */
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == OreDepositConfig.SPEC) {
            reload();
        }
    }
}
