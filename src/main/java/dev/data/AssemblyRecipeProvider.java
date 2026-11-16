package dev.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.OresAndDrillsMod;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/** Self-contained vanilla progression; conventional tags also accept other mods' materials. */
@EventBusSubscriber(modid = OresAndDrillsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class AssemblyRecipeProvider implements DataProvider {
    private final PackOutput.PathProvider recipes;
    private final PackOutput.PathProvider advancements;
    private final List<CompletableFuture<?>> writes = new ArrayList<>();
    private CachedOutput cache;

    public AssemblyRecipeProvider(PackOutput output) {
        recipes = output.createPathProvider(PackOutput.Target.DATA_PACK, "recipe");
        advancements = output.createPathProvider(PackOutput.Target.DATA_PACK, "advancement");
    }

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        event.getGenerator()
                .addProvider(
                        event.includeServer(),
                        new AssemblyRecipeProvider(event.getGenerator().getPackOutput()));
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        cache = output;
        writes.clear();
        shaped("iron_rod", 2, new String[] {"I", "I"}, "I", tag("c:ingots/iron"));
        shaped("drill_frame", new String[] {"I I", " I ", "I I"}, "I", tag("c:ingots/iron"));
        shaped(
                "mechanical_drive",
                new String[] {" N ", "IPI", " N "},
                "I",
                tag("c:ingots/iron"),
                "N",
                tag("c:nuggets/iron"),
                "P",
                item("minecraft:piston"));
        shaped(
                "drill_head",
                new String[] {" I ", "IRI", " R "},
                "I",
                tag("c:ingots/iron"),
                "R",
                tag("c:rods/iron"));
        shaped(
                "drill_assembly_table",
                new String[] {"CCC", "IFI", "I I"},
                "C",
                tag("minecraft:planks"),
                "I",
                tag("c:ingots/iron"),
                "F",
                item("ores_and_drills:drill_frame"));
        shaped(
                "burner_mining_drill",
                new String[] {" F ", "DCH", " I "},
                "F",
                item("ores_and_drills:drill_frame"),
                "D",
                item("ores_and_drills:mechanical_drive"),
                "C",
                item("minecraft:furnace"),
                "H",
                item("ores_and_drills:drill_head"),
                "I",
                tag("c:ingots/iron"));

        assembly(
                "electric_motor",
                120,
                part(item("ores_and_drills:mechanical_drive"), 1),
                part(tag("c:ingots/copper"), 8),
                part(tag("c:ingots/iron"), 4),
                part(tag("c:dusts/redstone"), 2));
        assembly(
                "fluid_manifold",
                100,
                part(tag("c:ingots/copper"), 6),
                part(tag("c:ingots/iron"), 2),
                part(item("minecraft:piston"), 1),
                part(item("minecraft:bucket"), 1));
        assembly(
                "reinforced_drill_head",
                160,
                part(item("ores_and_drills:drill_head"), 1),
                part(tag("c:gems/diamond"), 3),
                part(tag("c:ingots/iron"), 4));
        assembly(
                "precision_control",
                180,
                part(item("ores_and_drills:mechanical_drive"), 1),
                part(tag("c:ingots/gold"), 4),
                part(tag("c:dusts/redstone"), 8),
                part(tag("c:gems/quartz"), 4),
                part(item("minecraft:comparator"), 1));

        assembly(
                "exploration_drill",
                200,
                part(item("ores_and_drills:drill_frame"), 1),
                part(item("ores_and_drills:electric_motor"), 1),
                part(item("ores_and_drills:drill_head"), 1),
                part(tag("c:dusts/redstone"), 4),
                part(tag("c:glass_blocks/colorless"), 1));
        assembly(
                "electric_mining_drill",
                240,
                part(item("ores_and_drills:drill_frame"), 2),
                part(item("ores_and_drills:electric_motor"), 1),
                part(item("ores_and_drills:drill_head"), 2),
                part(item("ores_and_drills:mechanical_drive"), 1),
                part(tag("c:ingots/copper"), 4));
        assembly(
                "advanced_mining_drill",
                360,
                part(item("ores_and_drills:drill_frame"), 4),
                part(item("ores_and_drills:electric_motor"), 2),
                part(item("ores_and_drills:reinforced_drill_head"), 2),
                part(item("ores_and_drills:fluid_manifold"), 1),
                part(item("ores_and_drills:precision_control"), 1),
                part(tag("c:ingots/iron"), 8));
        assembly(
                "ultimate_mining_drill",
                480,
                part(item("ores_and_drills:drill_frame"), 8),
                part(item("ores_and_drills:electric_motor"), 4),
                part(item("ores_and_drills:reinforced_drill_head"), 4),
                part(item("ores_and_drills:fluid_manifold"), 2),
                part(item("ores_and_drills:precision_control"), 2),
                part(tag("c:ingots/netherite"), 2));
        assembly(
                "seismic_survey_station",
                400,
                part(item("ores_and_drills:drill_frame"), 4),
                part(item("ores_and_drills:electric_motor"), 2),
                part(item("ores_and_drills:mechanical_drive"), 2),
                part(item("ores_and_drills:fluid_manifold"), 1),
                part(item("ores_and_drills:precision_control"), 1),
                part(item("minecraft:anvil"), 1));
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    private void shaped(String name, String[] rows, Object... keys) {
        shaped(name, 1, rows, keys);
    }

    private void shaped(String name, int count, String[] rows, Object... keys) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "minecraft:crafting_shaped");
        recipe.addProperty("category", "misc");
        JsonArray pattern = new JsonArray();
        for (String row : rows) pattern.add(row);
        recipe.add("pattern", pattern);
        JsonObject key = new JsonObject();
        for (int i = 0; i < keys.length; i += 2)
            key.add((String) keys[i], (JsonElement) keys[i + 1]);
        recipe.add("key", key);
        JsonObject crafted = result(name);
        crafted.addProperty("count", count);
        recipe.add("result", crafted);
        save(name, recipe);
        // Crafting recipes are discoverable as soon as the player obtains an iron ingot.
        JsonObject advancement = new JsonObject();
        advancement.addProperty("parent", "minecraft:recipes/root");
        JsonObject criteria = new JsonObject();
        JsonObject obtainedIron = new JsonObject();
        obtainedIron.addProperty("trigger", "minecraft:inventory_changed");
        JsonObject conditions = new JsonObject();
        JsonArray items = new JsonArray();
        JsonObject iron = new JsonObject();
        iron.addProperty("items", "#c:ingots/iron");
        items.add(iron);
        conditions.add("items", items);
        obtainedIron.add("conditions", conditions);
        criteria.add("has_iron", obtainedIron);
        advancement.add("criteria", criteria);
        JsonArray requirement = new JsonArray();
        JsonArray criterion = new JsonArray();
        criterion.add("has_iron");
        requirement.add(criterion);
        advancement.add("requirements", requirement);
        JsonObject rewards = new JsonObject();
        JsonArray unlocked = new JsonArray();
        unlocked.add("ores_and_drills:" + name);
        rewards.add("recipes", unlocked);
        advancement.add("rewards", rewards);
        writes.add(
                DataProvider.saveStable(
                        cache, advancement, advancements.json(id("recipes/assembly/" + name))));
    }

    private void assembly(String name, int ticks, JsonObject... parts) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "ores_and_drills:drill_assembly");
        recipe.addProperty("duration", ticks);
        JsonArray ingredients = new JsonArray();
        for (JsonObject part : parts) ingredients.add(part);
        recipe.add("parts", ingredients);
        recipe.add("result", result(name));
        // Converted vanilla recipes keep their IDs so datapack overrides remain compatible.
        save(
                name.equals("exploration_drill") || name.equals("seismic_survey_station")
                        ? name
                        : "assembly/" + name,
                recipe);
    }

    private static JsonObject part(JsonElement ingredient, int count) {
        JsonObject part = new JsonObject();
        part.add("ingredient", ingredient);
        part.addProperty("count", count);
        return part;
    }

    private static JsonObject tag(String tag) {
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty("tag", tag);
        return ingredient;
    }

    private static JsonObject item(String item) {
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty("item", item);
        return ingredient;
    }

    private static JsonObject result(String name) {
        JsonObject result = new JsonObject();
        result.addProperty("id", "ores_and_drills:" + name);
        result.addProperty("count", 1);
        return result;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, path);
    }

    private void save(String name, JsonObject recipe) {
        writes.add(DataProvider.saveStable(cache, recipe, recipes.json(id(name))));
    }

    @Override
    public String getName() {
        return "Drill assembly recipes and unlocks";
    }
}
