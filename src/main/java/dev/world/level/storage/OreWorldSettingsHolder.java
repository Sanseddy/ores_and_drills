package dev.world.level.storage;

import java.util.List;

/** Internal mixin interface for Ores & Drills' level.dat settings. */
public interface OreWorldSettingsHolder {
    List<String> oresAndDrills$oreOverrides();

    void oresAndDrills$setOreOverrides(List<String> entries);

    List<String> oresAndDrills$oreDimensionOverrides();

    void oresAndDrills$setOreDimensionOverrides(List<String> entries);

    List<String> oresAndDrills$disabledOres();

    void oresAndDrills$setDisabledOres(List<String> entries);

    boolean oresAndDrills$vanillaLikeGeneration();

    void oresAndDrills$setVanillaLikeGeneration(boolean enabled);
}
