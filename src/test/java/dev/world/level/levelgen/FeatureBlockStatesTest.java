package dev.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureBlockStatesTest {
    private static List<String> placed(String json) {
        List<String> names = new ArrayList<>();
        FeatureBlockStates.forEachPlacedStateName(JsonParser.parseString(json), names::add);
        return names;
    }

    @Test
    void findsStatesOfANonOreFeatureInsideWeightedProviders() {
        // Eternal Starlight's hot spring: an ore-tagged block lining a lake, no ore configuration
        // anywhere.
        String hotSpring =
                """
                {"type": "eternal_starlight:lake", "config": {
                  "barrier": {"type": "minecraft:weighted_state_provider", "entries": [
                    {"data": {"Name": "eternal_starlight:springstone"}, "weight": 10},
                    {"data": {"Name": "eternal_starlight:thermal_springstone"}, "weight": 1}]},
                  "fluid": {"type": "minecraft:simple_state_provider",
                    "state": {"Name": "minecraft:water", "Properties": {"level": "0"}}}}}""";

        assertEquals(
                List.of(
                        "eternal_starlight:springstone",
                        "eternal_starlight:thermal_springstone",
                        "minecraft:water"),
                placed(hotSpring));
    }

    @Test
    void skipsRuleTestsThatOnlyNameTheReplacedBlock() {
        String ore =
                """
{"type": "minecraft:ore", "config": {"size": 9, "discard_chance_on_air_exposure": 0.0,
  "targets": [{"target": {"predicate_type": "minecraft:blockstate_match",
      "block_state": {"Name": "minecraft:gold_ore"}},
    "state": {"Name": "minecraft:iron_ore"}}]}}""";

        assertEquals(List.of("minecraft:iron_ore"), placed(ore));
    }

    @Test
    void walksNestedInlineFeatures() {
        String selector =
                """
{"type": "minecraft:random_selector", "config": {"default": {"feature": {
  "type": "minecraft:disk", "config": {"state_provider": {"fallback": {
    "type": "minecraft:simple_state_provider", "state": {"Name": "mod:clay_ore"}}}}},
  "placement": []}, "features": []}}""";

        assertEquals(List.of("mod:clay_ore"), placed(selector));
    }
}
