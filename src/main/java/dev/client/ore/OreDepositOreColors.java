package dev.client.ore;

import dev.FactoryExpansionMod;
import dev.world.level.levelgen.OreDepositOrePalette;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a multi-color palette (sorted dark to light) for an ore deposit from the most likely item
 * dropped by the original ore block, for {@link OreTintedTextureSource} to recolor the grayscale ore-layer
 * mask by luminance. Only depends on {@link ResourceManager} (no {@code ItemRenderer}/baked-model access)
 * since this is resolved during atlas stitching, which runs before model baking in the reload order.
 * <p>
 * Keyed by the ore block's own {@link ResourceLocation}, not a per-world {@code oreIndex}: block-tag data
 * (which {@link OreDepositOrePalette#availableIds()} partly depends on) isn't bound yet at atlas-stitch time
 * (title screen, before any world/server has loaded its data packs), so that list can differ in content and
 * order from the one a world later persists — an index-keyed bake would silently show one ore's texture on
 * a completely different ore once that mismatch shifts anything's position.
 */
public final class OreDepositOreColors {
    private static final int[] FALLBACK_PALETTE = {0xFFFFFF, 0xFFFFFF};
    private static final int PALETTE_SIZE = 5;
    /** Entries at/above this brightness are considered already-light and are skipped by the dark-half contrast boost in {@link #adjustPalette}. */
    private static final int DARK_CONTRAST_THRESHOLD = 170;
    private static final Map<ResourceLocation, int[]> CACHE = new ConcurrentHashMap<>();

    private OreDepositOreColors() {
    }

    public static int[] paletteForOre(ResourceManager resourceManager, ResourceLocation oreBlockId) {
        return CACHE.computeIfAbsent(oreBlockId, id -> resolvePalette(resourceManager, id));
    }

    static void clearCache() {
        CACHE.clear();
    }

    private static int[] resolvePalette(ResourceManager resourceManager, ResourceLocation oreBlockId) {
        if (!BuiltInRegistries.BLOCK.containsKey(oreBlockId)) {
            return FALLBACK_PALETTE;
        }

        Block ore = BuiltInRegistries.BLOCK.get(oreBlockId);
        ResourceLocation displayDropId = mostLikelyDropId(resourceManager, ore);
        int[] palette = paletteForItem(resourceManager, displayDropId);
        if (palette == null) {
            FactoryExpansionMod.LOGGER.warn(
                    "Ore deposit: no texture found for {} (guessed drop {}) — falling back to flat white tint",
                    oreBlockId, displayDropId
            );
            return FALLBACK_PALETTE;
        }
        return palette;
    }

    private static int[] paletteForItem(ResourceManager resourceManager, ResourceLocation itemId) {
        if (itemId == null) {
            return null;
        }

        for (ResourceLocation textureId : texturesFromItemModel(resourceManager, itemId)) {
            ResourceLocation texturePath = ResourceLocation.fromNamespaceAndPath(
                    textureId.getNamespace(), "textures/" + textureId.getPath() + ".png"
            );
            int[] palette = paletteFromTexture(resourceManager, texturePath);
            if (palette != null) {
                return palette;
            }
        }

        // Compatibility fallback for resource packs/items that provide a conventional texture but no model.
        ResourceLocation itemTexture = ResourceLocation.fromNamespaceAndPath(itemId.getNamespace(), "textures/item/" + itemId.getPath() + ".png");
        int[] palette = paletteFromTexture(resourceManager, itemTexture);
        if (palette != null) {
            return palette;
        }

        ResourceLocation blockTexture = ResourceLocation.fromNamespaceAndPath(itemId.getNamespace(), "textures/block/" + itemId.getPath() + ".png");
        return paletteFromTexture(resourceManager, blockTexture);
    }

    /** Resolves layer references through the item model and its parent chain, with child overrides. */
    private static List<ResourceLocation> texturesFromItemModel(ResourceManager resourceManager, ResourceLocation itemId) {
        ResourceLocation modelId = ResourceLocation.fromNamespaceAndPath(itemId.getNamespace(), "item/" + itemId.getPath());
        Map<String, TextureReference> textures = new LinkedHashMap<>();
        Set<ResourceLocation> visited = new HashSet<>();

        for (int depth = 0; depth < 32 && modelId != null && visited.add(modelId); depth++) {
            ResourceLocation modelPath = ResourceLocation.fromNamespaceAndPath(
                    modelId.getNamespace(), "models/" + modelId.getPath() + ".json"
            );
            Optional<Resource> modelResource = resourceManager.getResource(modelPath);
            if (modelResource.isEmpty()) {
                break;
            }

            try (Reader reader = modelResource.get().openAsReader()) {
                JsonObject model = JsonParser.parseReader(reader).getAsJsonObject();
                if (model.has("textures") && model.get("textures").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : model.getAsJsonObject("textures").entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) {
                            textures.putIfAbsent(entry.getKey(),
                                    new TextureReference(entry.getValue().getAsString(), modelId.getNamespace()));
                        }
                    }
                }
                String parent = stringProperty(model, "parent");
                modelId = parent == null ? null : parseLocation(parent, modelId.getNamespace());
            } catch (IOException | RuntimeException exception) {
                FactoryExpansionMod.LOGGER.warn("Ore deposit: failed to resolve item model {}", modelPath, exception);
                break;
            }
        }

        List<ResourceLocation> result = new ArrayList<>();
        Set<ResourceLocation> unique = new HashSet<>();
        for (String preferredKey : List.of("layer0", "all", "particle")) {
            addResolvedTexture(preferredKey, textures, result, unique);
        }
        for (String key : textures.keySet()) {
            addResolvedTexture(key, textures, result, unique);
        }
        return result;
    }

    private static void addResolvedTexture(
            String key,
            Map<String, TextureReference> textures,
            List<ResourceLocation> result,
            Set<ResourceLocation> unique
    ) {
        TextureReference reference = textures.get(key);
        Set<String> visitedKeys = new HashSet<>();
        while (reference != null && reference.value().startsWith("#") && visitedKeys.add(reference.value())) {
            reference = textures.get(reference.value().substring(1));
        }
        if (reference == null || reference.value().startsWith("#")) {
            return;
        }
        ResourceLocation texture = parseLocation(reference.value(), reference.defaultNamespace());
        if (texture != null && unique.add(texture)) {
            result.add(texture);
        }
    }

    private static ResourceLocation parseLocation(String value, String defaultNamespace) {
        return value.indexOf(':') >= 0
                ? ResourceLocation.tryParse(value)
                : ResourceLocation.fromNamespaceAndPath(defaultNamespace, value);
    }

    private static ResourceLocation mostLikelyDropId(ResourceManager resourceManager, Block ore) {
        Item item = mostLikelyLootItem(resourceManager, ore);
        if (item != Items.AIR) {
            return BuiltInRegistries.ITEM.getKey(item);
        }

        item = guessedDropItem(ore);
        if (item != Items.AIR) {
            return BuiltInRegistries.ITEM.getKey(item);
        }

        Item fallback = ore.asItem();
        return BuiltInRegistries.ITEM.getKey(fallback == Items.AIR ? Items.STONE : fallback);
    }

    private static Item guessedDropItem(Block ore) {
        ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(ore);
        if (oreId == null) {
            return Items.AIR;
        }

        String material = oreId.getPath()
                .replace("deepslate_", "")
                .replace("nether_", "")
                .replace("end_", "");
        if (material.startsWith("ore_")) {
            material = material.substring("ore_".length());
        }
        if (material.endsWith("_ore")) {
            material = material.substring(0, material.length() - "_ore".length());
        }

        Item raw = itemById(ResourceLocation.fromNamespaceAndPath(oreId.getNamespace(), "raw_" + material));
        if (raw != Items.AIR) {
            return raw;
        }

        return itemById(ResourceLocation.fromNamespaceAndPath(oreId.getNamespace(), material));
    }

    private static Item itemById(ResourceLocation itemId) {
        Item item = BuiltInRegistries.ITEM.get(itemId);
        return item == null ? Items.AIR : item;
    }

    private static Item mostLikelyLootItem(ResourceManager resourceManager, Block ore) {
        ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(ore);
        if (oreId == null) {
            return Items.AIR;
        }

        Optional<Resource> resource = lootTableResource(resourceManager, oreId);
        if (resource.isEmpty()) {
            return Items.AIR;
        }

        Map<Item, Integer> weights = new HashMap<>();
        try (Reader reader = resource.get().openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            collectWeightedItems(root, 1, false, weights);
        } catch (RuntimeException | IOException exception) {
            FactoryExpansionMod.LOGGER.warn("Ore deposit: failed to parse loot table for {}", oreId, exception);
            return Items.AIR;
        }

        if (weights.isEmpty()) {
            return Items.AIR;
        }

        Item oreItem = ore.asItem();
        return weights.entrySet().stream()
                .filter(entry -> weights.size() == 1 || entry.getKey() != oreItem)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Items.AIR);
    }

    private static Optional<Resource> lootTableResource(ResourceManager resourceManager, ResourceLocation oreId) {
        ResourceLocation modernPath = ResourceLocation.fromNamespaceAndPath(
                oreId.getNamespace(),
                "loot_table/blocks/" + oreId.getPath() + ".json"
        );
        Optional<Resource> modern = resourceManager.getResource(modernPath);
        if (modern.isPresent()) {
            return modern;
        }

        ResourceLocation legacyPath = ResourceLocation.fromNamespaceAndPath(
                oreId.getNamespace(),
                "loot_tables/blocks/" + oreId.getPath() + ".json"
        );
        return resourceManager.getResource(legacyPath);
    }

    private static void collectWeightedItems(JsonElement element, int inheritedWeight, boolean skipEntry, Map<Item, Integer> weights) {
        if (element == null || element.isJsonNull()) {
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectWeightedItems(child, inheritedWeight, skipEntry, weights);
            }
            return;
        }

        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        boolean skipThisEntry = skipEntry || hasSilkTouchCondition(object);
        int weight = inheritedWeight * Math.max(1, intProperty(object, "weight", 1));

        if (object.has("pools")) {
            collectWeightedItems(object.get("pools"), weight, skipThisEntry, weights);
        }
        if (object.has("entries")) {
            collectWeightedItems(object.get("entries"), weight, skipThisEntry, weights);
        }
        if (object.has("children")) {
            collectWeightedItems(object.get("children"), weight, skipThisEntry, weights);
        }

        String type = stringProperty(object, "type");
        String name = stringProperty(object, "name");
        if (!skipThisEntry && type != null && type.endsWith(":item") && name != null) {
            ResourceLocation itemId = ResourceLocation.tryParse(name);
            if (itemId == null) {
                return;
            }

            Item item = BuiltInRegistries.ITEM.get(itemId);
            if (item != Items.AIR) {
                weights.merge(item, weight, Integer::sum);
            }
        }
    }

    private static boolean hasSilkTouchCondition(JsonObject object) {
        if (!object.has("conditions")) {
            return false;
        }

        String conditions = object.get("conditions").toString();
        return conditions.contains("silk_touch");
    }

    private static String stringProperty(JsonObject object, String property) {
        return object.has(property) && object.get(property).isJsonPrimitive()
                ? object.get(property).getAsString()
                : null;
    }

    private static int intProperty(JsonObject object, String property, int fallback) {
        return object.has(property) && object.get(property).isJsonPrimitive()
                ? object.get(property).getAsInt()
                : fallback;
    }

    /**
     * Groups pixels into similar-color buckets (scored by {@code count × brightness²}, so a bright but
     * still-common cluster beats a merely-larger dark shading/outline cluster) and returns the real,
     * observed colors of the top {@link #PALETTE_SIZE} buckets, sorted dark to light — e.g. for diamonds,
     * the actual dark-to-light cyan tones the item texture uses, not one flat hue.
     */
    private static int[] paletteFromTexture(ResourceManager resourceManager, ResourceLocation texturePath) {
        Optional<Resource> resource = resourceManager.getResource(texturePath);
        if (resource.isEmpty()) {
            return null;
        }

        try (InputStream stream = resource.get().open();
             NativeImage image = NativeImage.read(stream)) {
            Map<Integer, long[]> buckets = new HashMap<>();
            Map<Integer, long[]> brightBuckets = new HashMap<>();
            long pixelCount = 0;
            long brightPixelCount = 0;

            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    int alpha = (abgr >> 24) & 0xFF;
                    if (alpha == 0) {
                        continue;
                    }

                    int pixelRed = abgr & 0xFF;
                    int pixelGreen = (abgr >> 8) & 0xFF;
                    int pixelBlue = (abgr >> 16) & 0xFF;
                    int max = Math.max(pixelRed, Math.max(pixelGreen, pixelBlue));
                    double brightness = max / 255.0D;
                    if (brightness < 0.08D) {
                        continue;
                    }

                    pixelCount++;
                    int bucketKey = ((pixelRed >> 4) << 8) | ((pixelGreen >> 4) << 4) | (pixelBlue >> 4);
                    long[] bucket = buckets.computeIfAbsent(bucketKey, ignored -> new long[4]);
                    bucket[0] += pixelRed;
                    bucket[1] += pixelGreen;
                    bucket[2] += pixelBlue;
                    bucket[3] += 1;
                    if (max >= 112) {
                        brightPixelCount++;
                        long[] brightBucket = brightBuckets.computeIfAbsent(bucketKey, ignored -> new long[4]);
                        brightBucket[0] += pixelRed;
                        brightBucket[1] += pixelGreen;
                        brightBucket[2] += pixelBlue;
                        brightBucket[3] += 1;
                    }
                }
            }

            if (buckets.isEmpty()) {
                return null;
            }

            Map<Integer, long[]> sourceBuckets = brightPixelCount >= Math.max(4, pixelCount / 4)
                    ? brightBuckets
                    : buckets;
            List<long[]> ranked = new ArrayList<>(sourceBuckets.values());
            ranked.sort(Comparator.comparingDouble(OreDepositOreColors::bucketScore).reversed());

            List<Integer> topColors = new ArrayList<>();
            for (int index = 0; index < Math.min(PALETTE_SIZE, ranked.size()); index++) {
                topColors.add(bucketColor(ranked.get(index)));
            }
            topColors.sort(Comparator.comparingInt(OreDepositOreColors::brightnessOf));

            // The bright-cluster ranking above deliberately ignores dark outline/shading pixels so the
            // ore's characteristic hue wins the palette. For naturally near-white metals (silver, tin,
            // aluminum, ...) that leaves the whole gradient light-on-light: the recolored flecks melt
            // into the equally-grey stone base and the deposit looks untinted. Re-anchor the dark end
            // with the texture's own darkest significant cluster from the full pixel set, so fleck
            // outlines keep the ore's real shading tone — data straight from the texture, never a
            // hard-coded per-ore color.
            if (sourceBuckets == brightBuckets) {
                int darkAnchor = darkestSignificantColor(buckets, pixelCount);
                if (darkAnchor >= 0 && brightnessOf(darkAnchor) < brightnessOf(topColors.get(0))) {
                    topColors.add(0, darkAnchor);
                }
            }

            if (topColors.size() == 1) {
                int only = topColors.get(0);
                return adjustPalette(new int[] {darken(only, 0.35D), only});
            }

            int[] palette = new int[topColors.size()];
            for (int index = 0; index < palette.length; index++) {
                palette[index] = topColors.get(index);
            }
            return adjustPalette(palette);
        } catch (IOException exception) {
            FactoryExpansionMod.LOGGER.warn("Ore deposit: failed to read texture {} for tint palette", texturePath, exception);
            return null;
        }
    }

    private record TextureReference(String value, String defaultNamespace) {
    }

    /**
     * The darkest color cluster that is still a real feature of the texture (an outline/shading tone shared
     * by a meaningful share of pixels), not a stray pixel. Returns {@code -1} when no cluster qualifies.
     */
    private static int darkestSignificantColor(Map<Integer, long[]> buckets, long pixelCount) {
        long minimumCount = Math.max(1, pixelCount / 32);
        int darkest = -1;
        int darkestBrightness = Integer.MAX_VALUE;
        for (long[] bucket : buckets.values()) {
            if (bucket[3] < minimumCount) {
                continue;
            }

            int color = bucketColor(bucket);
            int brightness = brightnessOf(color);
            if (brightness < darkestBrightness) {
                darkestBrightness = brightness;
                darkest = color;
            }
        }
        return darkest;
    }

    private static double bucketScore(long[] bucket) {
        long count = bucket[3];
        double brightness = brightnessOf(bucketColor(bucket)) / 255.0D;
        return count * (0.7D + brightness * brightness * 0.6D);
    }

    private static int bucketColor(long[] bucket) {
        long count = bucket[3];
        return rgb((int)(bucket[0] / count), (int)(bucket[1] / count), (int)(bucket[2] / count));
    }

    private static int brightnessOf(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        return Math.max(red, Math.max(green, blue));
    }

    private static int darken(int color, double amount) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        return rgb(
                (int)Math.round(red * (1.0D - amount)),
                (int)Math.round(green * (1.0D - amount)),
                (int)Math.round(blue * (1.0D - amount))
        );
    }

    /**
     * Extra darkening on the bottom half of the palette (the dark entries were reported as not dark enough
     * relative to the light ones) — but only for entries that are ALREADY reasonably dark. Darkening by
     * array position alone would also dull an ore whose whole palette is pale (e.g. fluorite): its "darkest"
     * entry is still light, and it doesn't need extra contrast just because it's the dimmer of two pale tones.
     */
    private static int[] adjustPalette(int[] palette) {
        int[] adjusted = new int[palette.length];
        int lastDarkIndex = (palette.length - 1) / 2;
        boolean brightPalette = averageBrightness(palette) >= 175;
        for (int index = 0; index < palette.length; index++) {
            int color = brightPalette ? palette[index] : darken(palette[index], 0.15D);
            if (index <= lastDarkIndex && brightnessOf(color) < DARK_CONTRAST_THRESHOLD) {
                color = darken(color, 0.15D);
            }
            adjusted[index] = color;
        }
        return adjusted;
    }

    private static int averageBrightness(int[] palette) {
        int total = 0;
        for (int color : palette) {
            total += brightnessOf(color);
        }
        return palette.length == 0 ? 0 : total / palette.length;
    }

    private static int rgb(int red, int green, int blue) {
        return (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
