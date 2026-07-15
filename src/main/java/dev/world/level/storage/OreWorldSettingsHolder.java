package dev.world.level.storage;

import java.util.List;

/** Internal bridge added to PrimaryLevelData for Ores & Drills' level.dat settings. */
public interface OreWorldSettingsHolder {
    List<String> factoryExpansion$oreOverrides();

    void factoryExpansion$setOreOverrides(List<String> entries);
}
