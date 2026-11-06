package dev.client.map;

import dev.client.ore.OreDepositOreColors;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Resolves each map color from the texture of the item actually dropped by its ore. No material or
 * mod-specific color table is involved.
 */
public final class OreMapColors {
    private static final Map<ResourceLocation, Integer> CACHE = new ConcurrentHashMap<>();

    private OreMapColors() {}

    /** Opaque RGB ({@code 0xRRGGBB}) for this ore on every map. */
    public static int colorFor(ResourceLocation oreId) {
        return CACHE.computeIfAbsent(oreId, OreMapColors::resolve);
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static int resolve(ResourceLocation oreId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getResourceManager() == null) {
            return oreId.hashCode() & 0xFFFFFF;
        }
        try {
            int color =
                    OreDepositOreColors.averageColorForOre(minecraft.getResourceManager(), oreId);
            return color >= 0 ? color : oreId.hashCode() & 0xFFFFFF;
        } catch (RuntimeException exception) {
            return oreId.hashCode() & 0xFFFFFF;
        }
    }
}
