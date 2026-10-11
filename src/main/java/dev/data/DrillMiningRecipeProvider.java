package dev.data;

import com.google.gson.JsonObject;
import dev.OresAndDrillsMod;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = OresAndDrillsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class DrillMiningRecipeProvider implements DataProvider {
    private final PackOutput.PathProvider output;

    public DrillMiningRecipeProvider(PackOutput output) {
        this.output = output.createPathProvider(PackOutput.Target.DATA_PACK, "recipe");
    }

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeServer(), new DrillMiningRecipeProvider(event.getGenerator().getPackOutput()));
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "ores_and_drills:drill_mining");
        recipe.addProperty("input", "#c:ores");
        return DataProvider.saveStable(cache, recipe,
                output.json(ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "drill_mining/ordinary_ores")));
    }

    @Override
    public String getName() { return "Drill mining recipes"; }
}
