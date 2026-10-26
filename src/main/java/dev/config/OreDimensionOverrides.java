package dev.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/** Per-world ore/dimension exclusions selected on the world creation screen. */
public final class OreDimensionOverrides {
    private static volatile Set<Entry> disabled = Set.of();

    private OreDimensionOverrides() {}

    public static void setDisabled(List<String> encoded) {
        Set<Entry> parsed = new LinkedHashSet<>();
        if (encoded != null) {
            for (String value : encoded) {
                String[] parts = value.split("\\|", -1);
                if (parts.length != 2) continue;
                ResourceLocation ore = ResourceLocation.tryParse(parts[0]);
                ResourceLocation dimension = ResourceLocation.tryParse(parts[1]);
                if (ore != null && dimension != null) parsed.add(new Entry(ore, dimension));
            }
        }
        disabled = Set.copyOf(parsed);
    }

    public static List<String> encoded() {
        return disabled.stream()
                .map(entry -> entry.ore() + "|" + entry.dimension())
                .sorted()
                .toList();
    }

    public static boolean isDisabled(Block ore, ResourceLocation dimension) {
        ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(ore);
        return oreId != null && disabled.contains(new Entry(oreId, dimension));
    }

    public static boolean isDisabled(ResourceLocation ore, ResourceLocation dimension) {
        return disabled.contains(new Entry(ore, dimension));
    }

    private record Entry(ResourceLocation ore, ResourceLocation dimension) {}
}
