package dev.client.ore;

import dev.OresAndDrillsMod;
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
 * Resolves the color palette of the item most likely dropped by an original ore block. The palette is not
 * painted onto anything: {@link OreSpeckLibrary} uses it to recognize which pixels of the original ore
 * texture are ore specks. Only depends on {@link ResourceManager} (no {@code ItemRenderer}/baked-model
 * access) since this is resolved during atlas stitching, which runs before model baking in the reload order.
 * <p>
 * Keyed by the ore block's own {@link ResourceLocation}, not a per-world {@code oreIndex}: block-tag data
 * (which {@link OreDepositOrePalette#availableIds()} partly depends on) isn't bound yet at atlas-stitch time
 * (title screen, before any world/server has loaded its data packs), so that list can differ in content and
 * order from the one a world later persists — an index-keyed bake would silently show one ore's texture on
 * a completely different ore once that mismatch shifts anything's position.
 */
public final class OreDepositOreColors {
    private static final int[] NO_PALETTE = new int[0];
    /** Enough distinct tones to cover an item's shading, highlights and outline without matching everything. */
    private static final int MAX_PALETTE_COLORS = 16;
    private static final int MINIMUM_BRIGHTNESS = 20;
    private static final Map<ResourceLocation, int[]> CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, int[]> DROP_CACHE = new ConcurrentHashMap<>();

    private OreDepositOreColors() {
    }

    public static int[] paletteForOre(ResourceManager resourceManager, ResourceLocation oreBlockId) {
        return CACHE.computeIfAbsent(oreBlockId, id -> resolvePalette(resourceManager, id));
    }

    static void clearCache() {
        CACHE.clear();
        DROP_CACHE.clear();
    }

    /** Resolves colors directly from the item id selected by the server-side loot table. */
    static int[] paletteForDrop(ResourceManager resourceManager, ResourceLocation dropId) {
        if (dropId == null) {
            return NO_PALETTE;
        }
        return DROP_CACHE.computeIfAbsent(dropId, id -> {
            int[] palette = paletteForItem(resourceManager, id);
            if (palette == null) {
                OresAndDrillsMod.LOGGER.debug(
                        "Ore deposit: no texture found for synchronized drop {} - specks are detected without a palette",
                        id
                );
                return NO_PALETTE;
            }
            return palette;
        });
    }

    private static int[] resolvePalette(ResourceManager resourceManager, ResourceLocation oreBlockId) {
        if (!BuiltInRegistries.BLOCK.containsKey(oreBlockId)) {
            return NO_PALETTE;
        }

        Block ore = BuiltInRegistries.BLOCK.get(oreBlockId);
        ResourceLocation displayDropId = OreDepositOrePalette.dropIdForOre(oreBlockId);
        ResourceLocation airId = BuiltInRegistries.ITEM.getKey(Items.AIR);
        if (displayDropId == null || displayDropId.equals(airId)) {
            // Used only before a world palette arrives. Loot tables are server data and are not normally
            // visible through the client asset ResourceManager used during atlas stitching.
            displayDropId = mostLikelyDropId(resourceManager, ore);
        }
        int[] palette = paletteForItem(resourceManager, displayDropId);
        if (palette == null) {
            OresAndDrillsMod.LOGGER.debug(
                    "Ore deposit: no texture found for {} (resolved drop {}) - specks are detected without a palette",
                    oreBlockId, displayDropId
            );
            return NO_PALETTE;
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

    private static List<ResourceLocation> texturesFromItemModel(ResourceManager resourceManager, ResourceLocation itemId) {
        ResourceLocation modelId = ResourceLocation.fromNamespaceAndPath(itemId.getNamespace(), "item/" + itemId.getPath());
        return texturesFromModel(resourceManager, modelId, List.of("layer0", "all", "particle"));
    }

    /**
     * Resolves texture references through a model and its parent chain, with child overrides; textures for
     * {@code preferredKeys} come first, then every other texture the model chain declares.
     */
    static List<ResourceLocation> texturesFromModel(
            ResourceManager resourceManager,
            ResourceLocation modelId,
            List<String> preferredKeys
    ) {
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
                OresAndDrillsMod.LOGGER.debug("Ore deposit: failed to resolve model {}", modelPath, exception);
                break;
            }
        }

        List<ResourceLocation> result = new ArrayList<>();
        Set<ResourceLocation> unique = new HashSet<>();
        for (String preferredKey : preferredKeys) {
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
            OresAndDrillsMod.LOGGER.debug("Ore deposit: failed to parse loot table for {}", oreId, exception);
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
     * The real colors of the drop texture: pixels grouped into similar-color buckets, most common first, up
     * to {@link #MAX_PALETTE_COLORS} bucket averages. Near-black and transparent pixels are ignored so the
     * palette does not match every dark crack of the host rock.
     */
    private static int[] paletteFromTexture(ResourceManager resourceManager, ResourceLocation texturePath) {
        Optional<Resource> resource = resourceManager.getResource(texturePath);
        if (resource.isEmpty()) {
            return null;
        }

        try (InputStream stream = resource.get().open();
             NativeImage image = NativeImage.read(stream)) {
            Map<Integer, long[]> buckets = new HashMap<>();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    int alpha = (abgr >> 24) & 0xFF;
                    int red = abgr & 0xFF;
                    int green = (abgr >> 8) & 0xFF;
                    int blue = (abgr >> 16) & 0xFF;
                    if (alpha < 128 || Math.max(red, Math.max(green, blue)) < MINIMUM_BRIGHTNESS) {
                        continue;
                    }

                    int bucketKey = ((red >> 4) << 8) | ((green >> 4) << 4) | (blue >> 4);
                    long[] bucket = buckets.computeIfAbsent(bucketKey, ignored -> new long[4]);
                    bucket[0] += red;
                    bucket[1] += green;
                    bucket[2] += blue;
                    bucket[3] += 1;
                }
            }

            if (buckets.isEmpty()) {
                return null;
            }

            return buckets.values().stream()
                    .sorted(Comparator.comparingLong((long[] bucket) -> bucket[3]).reversed())
                    .limit(MAX_PALETTE_COLORS)
                    .mapToInt(bucket -> (int) (bucket[0] / bucket[3]) << 16
                            | (int) (bucket[1] / bucket[3]) << 8
                            | (int) (bucket[2] / bucket[3]))
                    .toArray();
        } catch (IOException exception) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: failed to read texture {} for the drop palette", texturePath, exception);
            return null;
        }
    }

    private record TextureReference(String value, String defaultNamespace) {
    }
}
