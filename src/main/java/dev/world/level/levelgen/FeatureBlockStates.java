package dev.world.level.levelgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Block states a serialized feature configuration can place. Any feature type writes its block states with
 * the {@code BlockState} codec ({@code {"Name": id, "Properties": {...}}}), whether directly, through a state
 * provider or inside a nested feature, so walking the encoded tree finds them without knowing the feature.
 * Rule tests ({@code predicate_type}) only describe which blocks get replaced and are skipped.
 */
final class FeatureBlockStates {
    private FeatureBlockStates() {
    }

    static void forEachPlacedStateName(JsonElement element, Consumer<String> names) {
        if (element == null) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                forEachPlacedStateName(child, names);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("predicate_type")) {
            return;
        }
        JsonElement name = object.get("Name");
        if (name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()) {
            names.accept(name.getAsString());
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            forEachPlacedStateName(entry.getValue(), names);
        }
    }
}
