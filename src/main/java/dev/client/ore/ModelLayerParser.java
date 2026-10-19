package dev.client.ore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads which textures a block or item model JSON draws and the tint index of each, following the parent
 * chain, {@code elements} faces, composite {@code children} (NeoForge's composite loader) and the layers of
 * {@code item/generated}, where {@code layerN} is tinted with index {@code N}. Ids are plain strings and an id
 * without a namespace is a {@code minecraft} one, as in vanilla model files.
 */
final class ModelLayerParser {
    private static final int MAX_DEPTH = 32;
    private static final Set<String> GENERATED_ITEM_PARENTS = Set.of("minecraft:item/generated", "minecraft:builtin/generated");

    /** One drawn texture; {@code tintIndex} is -1 for an untinted layer. */
    record Layer(String texture, int tintIndex) {
    }

    private ModelLayerParser() {
    }

    /** Layers of {@code modelId} in drawing order; {@code loader} returns a model JSON or {@code null}. */
    static List<Layer> layers(String modelId, Function<String, JsonObject> loader) {
        Set<Layer> layers = new LinkedHashSet<>();
        collect(loader.apply(qualify(modelId)), loader, Map.of(), layers, 0);
        return List.copyOf(layers);
    }

    static String qualify(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    private static void collect(
            JsonObject model,
            Function<String, JsonObject> loader,
            Map<String, String> inheritedTextures,
            Set<Layer> layers,
            int depth
    ) {
        if (model == null || depth > MAX_DEPTH) {
            return;
        }
        Map<String, String> textures = new LinkedHashMap<>();
        JsonElement elements = null;
        JsonObject children = null;
        boolean generated = false;
        Set<String> visited = new HashSet<>();
        JsonObject current = model;
        for (int level = 0; current != null && level < MAX_DEPTH; level++) {
            if (current.has("textures") && current.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : current.getAsJsonObject("textures").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            if (elements == null && current.has("elements") && current.get("elements").isJsonArray()) {
                elements = current.get("elements");
            }
            if (children == null && current.has("children") && current.get("children").isJsonObject()) {
                children = current.getAsJsonObject("children");
            }
            if (!current.has("parent") || !current.get("parent").isJsonPrimitive()) {
                break;
            }
            String parent = qualify(current.get("parent").getAsString());
            if (!visited.add(parent)) {
                break;
            }
            if (GENERATED_ITEM_PARENTS.contains(parent)) {
                generated = true;
                break;
            }
            current = loader.apply(parent);
        }
        // A composite model's textures are visible to its children.
        inheritedTextures.forEach(textures::putIfAbsent);

        if (children != null) {
            for (Map.Entry<String, JsonElement> child : children.entrySet()) {
                if (child.getValue().isJsonObject()) {
                    collect(child.getValue().getAsJsonObject(), loader, textures, layers, depth + 1);
                }
            }
            return;
        }
        if (elements != null) {
            for (JsonElement element : elements.getAsJsonArray()) {
                if (!element.isJsonObject() || !element.getAsJsonObject().has("faces")
                        || !element.getAsJsonObject().get("faces").isJsonObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> face : element.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
                    if (!face.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject faceObject = face.getValue().getAsJsonObject();
                    if (!faceObject.has("texture") || !faceObject.get("texture").isJsonPrimitive()) {
                        continue;
                    }
                    String texture = resolve(faceObject.get("texture").getAsString(), textures);
                    int tintIndex = faceObject.has("tintindex") && faceObject.get("tintindex").isJsonPrimitive()
                            ? faceObject.get("tintindex").getAsInt() : -1;
                    if (texture != null) {
                        layers.add(new Layer(texture, tintIndex));
                    }
                }
            }
            return;
        }
        if (generated) {
            for (int layer = 0; textures.containsKey("layer" + layer); layer++) {
                String texture = resolve("#layer" + layer, textures);
                if (texture != null) {
                    layers.add(new Layer(texture, layer));
                }
            }
        }
    }

    private static String resolve(String value, Map<String, String> textures) {
        String reference = value;
        Set<String> visitedKeys = new HashSet<>();
        while (reference != null && reference.startsWith("#") && visitedKeys.add(reference)) {
            reference = textures.get(reference.substring(1));
        }
        return reference == null || reference.startsWith("#") ? null : qualify(reference);
    }
}
