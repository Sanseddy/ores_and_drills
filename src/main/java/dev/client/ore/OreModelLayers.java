package dev.client.ore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.OresAndDrillsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The texture layers a block or item model draws, each with the tint index its faces use, and the colors the
 * game multiplies into tinted layers. Many mods (GregTech and other material-based ones) ship one grey ore
 * texture and color it per material through {@code BlockColor}/{@code ItemColor}; reading such a texture
 * without its tint gives grey specks. Models are read from the active resource packs, including
 * runtime-generated ones (see {@link ModelLayerParser} for what is followed).
 */
final class OreModelLayers {
    /** One drawn texture; {@code tintIndex} is -1 for an untinted layer. */
    record Layer(ResourceLocation texture, int tintIndex) {
    }

    private OreModelLayers() {
    }

    /** Layers of a model in drawing order; empty when the model cannot be read. */
    static List<Layer> layers(ResourceManager resourceManager, ResourceLocation modelId) {
        List<Layer> layers = new ArrayList<>();
        try {
            for (ModelLayerParser.Layer layer : ModelLayerParser.layers(
                    modelId.toString(), id -> loadModel(resourceManager, ResourceLocation.tryParse(id)))) {
                ResourceLocation texture = ResourceLocation.tryParse(layer.texture());
                if (texture != null) {
                    layers.add(new Layer(texture, layer.tintIndex()));
                }
            }
        } catch (RuntimeException exception) {
            // A malformed model only loses the tint hint; the textures are still read as they are.
            OresAndDrillsMod.LOGGER.debug("Ore deposit: failed to read the layers of model {}", modelId, exception);
        }
        return List.copyOf(layers);
    }

    /** The RGB the game multiplies into {@code tintIndex} of the block, or white when it has no tint. */
    static int blockTint(Block block, int tintIndex) {
        if (tintIndex < 0) {
            return 0xFFFFFF;
        }
        try {
            // Without a level or position, as for an item: material tints do not depend on the world.
            return Minecraft.getInstance().getBlockColors().getColor(block.defaultBlockState(), null, null, tintIndex)
                    & 0xFFFFFF;
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: block color of {} needs a level; using its texture as is",
                    block, exception);
            return 0xFFFFFF;
        }
    }

    /** The RGB the game multiplies into {@code tintIndex} of the item, or white when it has no tint. */
    static int itemTint(Item item, int tintIndex) {
        if (tintIndex < 0) {
            return 0xFFFFFF;
        }
        try {
            return Minecraft.getInstance().getItemColors().getColor(new ItemStack(item), tintIndex) & 0xFFFFFF;
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: item color of {} failed; using its texture as is", item, exception);
            return 0xFFFFFF;
        }
    }

    /** Textures of the given layers that are tinted, mapped to their first tint index. */
    static Map<ResourceLocation, Integer> tintIndices(List<Layer> layers) {
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (Layer layer : layers) {
            if (layer.tintIndex() >= 0) {
                result.putIfAbsent(layer.texture(), layer.tintIndex());
            }
        }
        return result;
    }

    /** Distinct textures of the given layers, in drawing order. */
    static List<ResourceLocation> textures(List<Layer> layers) {
        List<ResourceLocation> result = new ArrayList<>();
        for (Layer layer : layers) {
            if (!result.contains(layer.texture())) {
                result.add(layer.texture());
            }
        }
        return result;
    }

    @Nullable
    private static JsonObject loadModel(ResourceManager resourceManager, @Nullable ResourceLocation modelId) {
        if (modelId == null) {
            return null;
        }
        ResourceLocation path = ResourceLocation.fromNamespaceAndPath(
                modelId.getNamespace(), "models/" + modelId.getPath() + ".json");
        Optional<Resource> resource = resourceManager.getResource(path);
        if (resource.isEmpty()) {
            return null;
        }
        try (Reader reader = resource.get().openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (IOException | RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug("Ore deposit: failed to read model {}", path, exception);
            return null;
        }
    }
}
