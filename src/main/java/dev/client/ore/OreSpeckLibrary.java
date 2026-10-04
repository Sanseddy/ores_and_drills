package dev.client.ore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.OresAndDrillsMod;
import dev.client.ore.speck.ArgbImage;
import dev.client.ore.speck.OreSpeckAnalysis;
import dev.client.ore.speck.OreSpeckLayout;
import dev.world.level.levelgen.OreVisualStages;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns an ore block into its speck layout: reads the ore's own block texture, identifies the host rock it
 * was painted on, detects the specks with the drop palette and plans every fill state from them.
 * Cached per (ore, drop, resolution) and cleared on every resource reload, so resource pack changes apply.
 */
final class OreSpeckLibrary {
    /** Rocks ore textures are commonly painted on; their pixels are excluded from speck detection. */
    private static final List<ResourceLocation> HOST_TEXTURES = List.of(
            ResourceLocation.withDefaultNamespace("block/stone"),
            ResourceLocation.withDefaultNamespace("block/deepslate"),
            ResourceLocation.withDefaultNamespace("block/netherrack"),
            ResourceLocation.withDefaultNamespace("block/end_stone"),
            ResourceLocation.withDefaultNamespace("block/tuff"),
            ResourceLocation.withDefaultNamespace("block/granite"),
            ResourceLocation.withDefaultNamespace("block/diorite"),
            ResourceLocation.withDefaultNamespace("block/andesite"),
            ResourceLocation.withDefaultNamespace("block/calcite"),
            ResourceLocation.withDefaultNamespace("block/blackstone"),
            ResourceLocation.withDefaultNamespace("block/basalt_side"),
            ResourceLocation.withDefaultNamespace("block/smooth_basalt"),
            ResourceLocation.withDefaultNamespace("block/sandstone"),
            ResourceLocation.withDefaultNamespace("block/gravel")
    );
    private static final List<String> BLOCK_TEXTURE_KEYS = List.of("all", "side", "particle", "texture", "north", "end", "top");
    /**
     * Highest generated sprite resolution. Every pre-stitched slot has this size, so the atlas grows with its
     * square: 32 already takes four times the memory of 16.
     */
    static final int MAXIMUM_RESOLUTION = 32;
    private static final Map<Key, List<OreSpeckLayout>> CACHE = new ConcurrentHashMap<>();
    private static volatile List<ArgbImage> hostImages;

    private OreSpeckLibrary() {
    }

    static void clearCache() {
        CACHE.clear();
        hostImages = null;
    }

    /**
     * The resolution of the active resource packs, read from their stone texture: 16 for vanilla-sized packs,
     * 32 for 32x packs, capped at {@link #MAXIMUM_RESOLUTION}. Specks are generated on this grid so they line up
     * with the pixels of the rock they are drawn on.
     */
    static int resolutionFor(ResourceManager resourceManager) {
        ArgbImage stone = readImage(resourceManager, HOST_TEXTURES.get(0));
        int frame = stone == null ? OreSpeckAnalysis.GRID : Math.min(stone.width(), stone.height());
        int resolution = OreSpeckAnalysis.GRID;
        while (resolution * 2 <= Math.min(frame, MAXIMUM_RESOLUTION)) {
            resolution *= 2;
        }
        return resolution;
    }

    /**
     * {@code variants} different arrangements of the same detected specks; the ore is analyzed only once.
     *
     * @param dropId the server-resolved loot drop, or {@code null} before a world palette is known (the drop
     *               is then guessed from the client-visible data)
     */
    static List<OreSpeckLayout> layoutsFor(
            ResourceManager resourceManager,
            @Nullable ResourceLocation oreId,
            @Nullable ResourceLocation dropId,
            int variants,
            int resolution
    ) {
        if (oreId == null) {
            return Collections.nCopies(variants, OreSpeckLayout.plan(OreSpeckAnalysis.empty(resolution), 0L, 1.0D));
        }
        ResourceLocation airId = BuiltInRegistries.ITEM.getKey(Items.AIR);
        ResourceLocation effectiveDrop = dropId == null || dropId.equals(airId) ? null : dropId;
        return CACHE.computeIfAbsent(
                new Key(oreId, effectiveDrop, variants, resolution),
                key -> buildLayouts(resourceManager, key)
        );
    }

    private static List<OreSpeckLayout> buildLayouts(ResourceManager resourceManager, Key key) {
        int[] palette = key.dropId() != null
                ? OreDepositOreColors.paletteForDrop(resourceManager, key.dropId())
                : OreDepositOreColors.paletteForOre(resourceManager, key.oreId());

        // A model can reference several textures: a resource pack may draw the ore as a stone layer plus a
        // transparent overlay, or give the ore different faces. The overlay wins; otherwise the texture with
        // the most ore pixels. Known rock textures are never the ore itself.
        OreSpeckAnalysis analysis = OreSpeckAnalysis.empty(key.resolution());
        ResourceLocation oreTexture = null;
        for (ResourceLocation candidate : blockTextures(resourceManager, key.oreId())) {
            if (HOST_TEXTURES.contains(candidate)) {
                continue;
            }
            ArgbImage image = readImage(resourceManager, candidate);
            if (image == null) {
                continue;
            }
            ArgbImage host = OreSpeckAnalysis.pickHost(image, hostImages(resourceManager), key.resolution());
            OreSpeckAnalysis candidateAnalysis = OreSpeckAnalysis.analyze(image, host, palette, key.resolution());
            if (isBetter(candidateAnalysis, analysis)) {
                analysis = candidateAnalysis;
                oreTexture = candidate;
            }
        }
        if (analysis.total() == 0) {
            analysis = OreSpeckAnalysis.synthetic(palette, key.resolution());
        }

        OresAndDrillsMod.LOGGER.debug(
                "Ore deposit: {} (texture {}, drop {}) -> {}",
                key.oreId(), oreTexture, key.dropId(), analysis.summary()
        );
        List<OreSpeckLayout> layouts = new ArrayList<>(key.variants());
        for (int variant = 0; variant < key.variants(); variant++) {
            layouts.add(OreSpeckLayout.plan(analysis, seedFor(key.oreId(), variant), OreVisualStages.maximumFillFactor()));
        }
        return List.copyOf(layouts);
    }

    private static boolean isBetter(OreSpeckAnalysis candidate, OreSpeckAnalysis current) {
        if (candidate.total() == 0) {
            return false;
        }
        if (current.total() == 0) {
            return true;
        }
        boolean candidateOverlay = candidate.source() == OreSpeckAnalysis.Source.OVERLAY;
        boolean currentOverlay = current.source() == OreSpeckAnalysis.Source.OVERLAY;
        if (candidateOverlay != currentOverlay) {
            return candidateOverlay;
        }
        return candidate.totalPixels() > current.totalPixels();
    }

    /**
     * Stable across sessions and worlds, so a given variant of an ore always gets the same arrangement, while
     * the variants of one ore differ from each other.
     */
    private static long seedFor(ResourceLocation oreId, int variant) {
        long hash = oreId.toString().hashCode() * 31L + variant;
        hash = (hash ^ (hash >>> 33)) * 0xFF51AFD7ED558CCDL;
        hash = (hash ^ (hash >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return hash ^ (hash >>> 33);
    }

    private static List<ArgbImage> hostImages(ResourceManager resourceManager) {
        List<ArgbImage> images = hostImages;
        if (images == null) {
            List<ArgbImage> loaded = new ArrayList<>(HOST_TEXTURES.size());
            for (ResourceLocation texture : HOST_TEXTURES) {
                ArgbImage image = readImage(resourceManager, texture);
                if (image != null) {
                    loaded.add(image);
                }
            }
            images = List.copyOf(loaded);
            hostImages = images;
        }
        return images;
    }

    /** Textures of the ore block's own model (via its blockstate), then the conventional texture path. */
    private static List<ResourceLocation> blockTextures(ResourceManager resourceManager, ResourceLocation blockId) {
        Set<ResourceLocation> textures = new LinkedHashSet<>();
        for (ResourceLocation modelId : blockStateModels(resourceManager, blockId)) {
            textures.addAll(OreDepositOreColors.texturesFromModel(resourceManager, modelId, BLOCK_TEXTURE_KEYS));
        }
        textures.add(ResourceLocation.fromNamespaceAndPath(blockId.getNamespace(), "block/" + blockId.getPath()));
        return new ArrayList<>(textures);
    }

    private static List<ResourceLocation> blockStateModels(ResourceManager resourceManager, ResourceLocation blockId) {
        ResourceLocation path = ResourceLocation.fromNamespaceAndPath(blockId.getNamespace(), "blockstates/" + blockId.getPath() + ".json");
        Optional<Resource> resource = resourceManager.getResource(path);
        if (resource.isEmpty()) {
            return List.of();
        }

        List<ResourceLocation> models = new ArrayList<>();
        try (Reader reader = resource.get().openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("variants") && root.get("variants").isJsonObject()) {
                for (Map.Entry<String, JsonElement> variant : root.getAsJsonObject("variants").entrySet()) {
                    addModel(variant.getValue(), blockId.getNamespace(), models);
                }
            }
            if (root.has("multipart") && root.get("multipart").isJsonArray()) {
                for (JsonElement part : root.getAsJsonArray("multipart")) {
                    if (part.isJsonObject() && part.getAsJsonObject().has("apply")) {
                        addModel(part.getAsJsonObject().get("apply"), blockId.getNamespace(), models);
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: failed to read blockstate {}", path, exception);
        }
        return models;
    }

    private static void addModel(JsonElement element, String defaultNamespace, List<ResourceLocation> models) {
        JsonElement first = element.isJsonArray() && !element.getAsJsonArray().isEmpty()
                ? element.getAsJsonArray().get(0)
                : element;
        if (!first.isJsonObject() || !first.getAsJsonObject().has("model")) {
            return;
        }
        String model = first.getAsJsonObject().get("model").getAsString();
        ResourceLocation modelId = model.indexOf(':') >= 0
                ? ResourceLocation.tryParse(model)
                : ResourceLocation.fromNamespaceAndPath(defaultNamespace, model);
        if (modelId != null && !models.contains(modelId)) {
            models.add(modelId);
        }
    }

    @Nullable
    static ArgbImage readImage(ResourceManager resourceManager, ResourceLocation textureId) {
        ResourceLocation path = ResourceLocation.fromNamespaceAndPath(textureId.getNamespace(), "textures/" + textureId.getPath() + ".png");
        Optional<Resource> resource = resourceManager.getResource(path);
        if (resource.isEmpty()) {
            return null;
        }

        try (InputStream stream = resource.get().open();
             NativeImage image = NativeImage.read(stream)) {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = abgrToArgb(image.getPixelRGBA(x, y));
                }
            }
            return new ArgbImage(width, height, pixels);
        } catch (IOException | RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: failed to read texture {}", path, exception);
            return null;
        }
    }

    static NativeImage toNativeImage(ArgbImage image) {
        NativeImage result = new NativeImage(image.width(), image.height(), false);
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                result.setPixelRGBA(x, y, abgrToArgb(image.get(x, y)));
            }
        }
        return result;
    }

    /** Swapping red and blue is its own inverse, so this converts in both directions. */
    private static int abgrToArgb(int color) {
        return (color & 0xFF00FF00) | ((color & 0xFF) << 16) | ((color >> 16) & 0xFF);
    }

    private record Key(ResourceLocation oreId, @Nullable ResourceLocation dropId, int variants, int resolution) {
        Key {
            Objects.requireNonNull(oreId);
        }
    }
}
