package dev.client.map;

import dev.client.ore.OreDepositOreColors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flat, high-contrast resource colours in the spirit of Factorio's map view. Vanilla ore blocks all share the
 * stone map colour, so the colour comes from the ore's material ({@code #c:ores/<material>} or its id),
 * falling back to the most saturated tone of the ore's own texture for unknown modded ores.
 */
public final class OreMapColors {
    private static final Map<ResourceLocation, Integer> CACHE = new ConcurrentHashMap<>();

    private OreMapColors() {
    }

    /** Opaque RGB ({@code 0xRRGGBB}) for this ore on every map. */
    public static int colorFor(ResourceLocation oreId) {
        return CACHE.computeIfAbsent(oreId, OreMapColors::resolve);
    }

    private static int resolve(ResourceLocation oreId) {
        if (BuiltInRegistries.BLOCK.containsKey(oreId)) {
            Block block = BuiltInRegistries.BLOCK.get(oreId);
            Integer tagged = block.defaultBlockState().getTags()
                    .filter(tag -> tag.location().getNamespace().equals("c"))
                    .map(tag -> tag.location().getPath())
                    .filter(path -> path.startsWith("ores/"))
                    .map(path -> OreMapPalette.materialColor(path.substring("ores/".length())))
                    .filter(color -> color != null)
                    .findFirst()
                    .orElse(null);
            if (tagged != null) {
                return tagged;
            }
        }
        Integer named = OreMapPalette.materialColor(oreId.getPath());
        if (named != null) {
            return named;
        }
        Integer textured = textureColor(oreId);
        return textured != null ? textured : Mth.hsvToRgb((oreId.hashCode() & 0xFFFF) / 65536.0F, 0.6F, 0.85F) & 0xFFFFFF;
    }

    private static Integer textureColor(ResourceLocation oreId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getResourceManager() == null) {
            return null;
        }
        try {
            return OreMapPalette.mostVivid(OreDepositOreColors.paletteForOre(minecraft.getResourceManager(), oreId));
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
