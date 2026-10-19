package dev.client.ore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelLayerParserTest {
    /** Vanilla's stone model chain, as far as the parser needs it. */
    private static final Map<String, String> VANILLA = Map.of(
            "minecraft:block/stone", """
                    {"parent": "minecraft:block/cube_all", "textures": {"all": "minecraft:block/stone"}}""",
            "minecraft:block/cube_all", """
                    {"parent": "block/cube", "textures": {"particle": "#all", "down": "#all", "up": "#all",
                      "north": "#all", "east": "#all", "south": "#all", "west": "#all"}}""",
            "minecraft:block/cube", """
                    {"parent": "block/block", "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                      "down": {"texture": "#down", "cullface": "down"},
                      "up": {"texture": "#up", "cullface": "up"}}}]}""",
            "minecraft:block/block", "{}"
    );

    private static List<ModelLayerParser.Layer> layers(String modelId, Map<String, String> models) {
        return ModelLayerParser.layers(modelId, id -> {
            String json = models.containsKey(id) ? models.get(id) : VANILLA.get(id);
            return json == null ? null : (JsonObject) JsonParser.parseString(json);
        });
    }

    @Test
    void gregTechCompositeOreYieldsTheStoneAndBothTintedLayers() {
        // gtceu:models/block/ore.json as cloned per ore by GregTech's OreBlockRenderer (faces shortened).
        String copperOre = """
                {"loader": "neoforge:composite", "textures": {"particle": "gtceu:block/material_sets/dull/ore"},
                 "children": {
                   "base_stone": {"render_type": "solid", "parent": "minecraft:block/stone"},
                   "ore_texture": {"parent": "block/block", "render_type": "translucent",
                     "textures": {"particle": "#layer0",
                       "layer0": "gtceu:block/material_sets/dull/ore",
                       "layer1": "gtceu:block/material_sets/dull/ore_layer2"},
                     "elements": [
                       {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                         "north": {"texture": "#layer0", "cullface": "north", "tintindex": 0},
                         "south": {"texture": "#layer0", "cullface": "south", "tintindex": 0}}},
                       {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                         "north": {"texture": "#layer1", "cullface": "north", "tintindex": 1}}}]}}}""";

        assertEquals(List.of(
                new ModelLayerParser.Layer("minecraft:block/stone", -1),
                new ModelLayerParser.Layer("gtceu:block/material_sets/dull/ore", 0),
                new ModelLayerParser.Layer("gtceu:block/material_sets/dull/ore_layer2", 1)
        ), layers("gtceu:block/copper_ore", Map.of("gtceu:block/copper_ore", copperOre)));
    }

    @Test
    void generatedItemLayersAreTintedByTheirIndex() {
        // GregTech's raw ore: the item model delegates to its material set, whose parent is item/generated.
        Map<String, String> models = Map.of(
                "gtceu:item/raw_copper", """
                        {"parent": "gtceu:item/material_sets/dull/raw_ore"}""",
                "gtceu:item/material_sets/dull/raw_ore", """
                        {"parent": "item/generated", "textures": {
                          "layer0": "gtceu:item/material_sets/dull/raw_ore",
                          "layer1": "gtceu:item/material_sets/dull/raw_ore_secondary"}}""");

        assertEquals(List.of(
                new ModelLayerParser.Layer("gtceu:item/material_sets/dull/raw_ore", 0),
                new ModelLayerParser.Layer("gtceu:item/material_sets/dull/raw_ore_secondary", 1)
        ), layers("gtceu:item/raw_copper", models));
    }

    @Test
    void plainOreKeepsItsUntintedTexture() {
        Map<String, String> models = Map.of("minecraft:block/iron_ore", """
                {"parent": "minecraft:block/cube_all", "textures": {"all": "minecraft:block/iron_ore"}}""");

        assertEquals(List.of(new ModelLayerParser.Layer("minecraft:block/iron_ore", -1)),
                layers("minecraft:block/iron_ore", models));
    }

    @Test
    void missingOrCyclicModelsGiveNoLayers() {
        Map<String, String> models = Map.of(
                "mod:block/a", "{\"parent\": \"mod:block/b\"}",
                "mod:block/b", "{\"parent\": \"mod:block/a\"}");

        assertEquals(List.of(), layers("mod:block/a", models));
        assertEquals(List.of(), layers("mod:block/missing", models));
    }
}
