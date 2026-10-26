package dev.config;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Ores switched off on the world creation screen, persisted in that world's {@code level.dat}.
 * Entries are block ids or {@code #tag} ids, like the common config's server-wide {@code
 * ignored_ores}.
 */
public final class OreWorldDisabledOres {
    private static volatile List<String> entries = List.of();

    private OreWorldDisabledOres() {}

    public static List<String> entries() {
        return entries;
    }

    /**
     * Called while a world is created or its level.dat is read, before biome modifiers are applied.
     */
    public static void set(List<String> disabled) {
        entries = disabled == null ? List.of() : List.copyOf(disabled);
    }

    public static boolean isDisabled(Block block) {
        return matches(entries, block);
    }

    /** Shared id/tag matching for this list and the common config's ignored ores. */
    public static boolean matches(List<? extends String> disabled, Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return false;
        }
        for (String entry : disabled) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            if (entry.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(entry.substring(1));
                if (tagId != null
                        && block.defaultBlockState().is(TagKey.create(Registries.BLOCK, tagId))) {
                    return true;
                }
            } else if (entry.equals(id.toString())) {
                return true;
            }
        }
        return false;
    }
}
