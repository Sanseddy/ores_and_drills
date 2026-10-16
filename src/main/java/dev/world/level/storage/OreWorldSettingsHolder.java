package dev.world.level.storage;

import java.util.List;

/** Internal mixin interface for Ores & Drills' level.dat settings. */
public interface OreWorldSettingsHolder {
    List<String> oresAndDrills$oreOverrides();

    void oresAndDrills$setOreOverrides(List<String> entries);

    List<String> oresAndDrills$oreDimensionOverrides();

    void oresAndDrills$setOreDimensionOverrides(List<String> entries);
}
