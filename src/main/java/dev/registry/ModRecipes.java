package dev.registry;

import dev.OresAndDrillsMod;
import dev.recipe.DrillMiningRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModRecipes {
    public static final DeferredRegister<RecipeType<?>> TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, OresAndDrillsMod.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, OresAndDrillsMod.MOD_ID);
    public static final Supplier<RecipeType<DrillMiningRecipe>> DRILL_MINING = TYPES.register("drill_mining",
            () -> RecipeType.simple(ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "drill_mining")));
    public static final Supplier<RecipeSerializer<DrillMiningRecipe>> DRILL_MINING_SERIALIZER =
            SERIALIZERS.register("drill_mining", DrillMiningRecipe.Serializer::new);
    private ModRecipes() {}
}
