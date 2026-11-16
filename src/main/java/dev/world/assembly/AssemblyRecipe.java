package dev.world.assembly;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.registry.ModRecipes;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/** Data-driven, counted ingredients. Slots are numbered to make the assembly plan unambiguous. */
public record AssemblyRecipe(List<Part> parts, ItemStack result, int duration)
        implements Recipe<AssemblyInput> {
    public static final MapCodec<AssemblyRecipe> CODEC =
            RecordCodecBuilder.mapCodec(
                    instance ->
                            instance.group(
                                            Part.CODEC
                                                    .listOf()
                                                    .fieldOf("parts")
                                                    .forGetter(AssemblyRecipe::parts),
                                            ItemStack.STRICT_CODEC
                                                    .fieldOf("result")
                                                    .forGetter(AssemblyRecipe::result),
                                            Codec.intRange(20, 12000)
                                                    .fieldOf("duration")
                                                    .forGetter(AssemblyRecipe::duration))
                                    .apply(instance, AssemblyRecipe::new));

    public AssemblyRecipe {
        parts = List.copyOf(parts);
        result = result.copy();
        if (parts.isEmpty()
                || parts.size() > 6
                || result.isEmpty()
                || result.getCount() > result.getMaxStackSize()
                || duration < 20
                || duration > 12000) {
            throw new IllegalArgumentException(
                    "Assembly requires 1..6 parts, a result and 20..12000 ticks");
        }
    }

    public record Part(Ingredient ingredient, int count) {
        public static final Codec<Part> CODEC =
                RecordCodecBuilder.create(
                        instance ->
                                instance.group(
                                                Ingredient.CODEC_NONEMPTY
                                                        .fieldOf("ingredient")
                                                        .forGetter(Part::ingredient),
                                                Codec.intRange(1, 64)
                                                        .optionalFieldOf("count", 1)
                                                        .forGetter(Part::count))
                                        .apply(instance, Part::new));

        public Part {
            if (ingredient.isEmpty() || count < 1 || count > 64) {
                throw new IllegalArgumentException(
                        "Assembly part must be nonempty and require 1..64 items");
            }
        }

        public boolean accepts(ItemStack stack) {
            return ingredient.test(stack) && stack.getCount() >= count;
        }
    }

    @Override
    public boolean matches(AssemblyInput input, Level level) {
        for (int i = 0; i < 6; i++) {
            if (i < parts.size()
                    ? !parts.get(i).accepts(input.getItem(i))
                    : !input.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(AssemblyInput input, HolderLookup.Provider registries) {
        return result.copy();
    }

    @Override
    public ItemStack result() {
        return result.copy();
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return result.copy();
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        parts.forEach(part -> ingredients.add(part.ingredient()));
        return ingredients;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= parts.size();
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.DRILL_ASSEMBLY_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.DRILL_ASSEMBLY.get();
    }

    public static final class Serializer implements RecipeSerializer<AssemblyRecipe> {
        @Override
        public MapCodec<AssemblyRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, AssemblyRecipe> streamCodec() {
            return ByteBufCodecs.fromCodecWithRegistries(CODEC.codec());
        }
    }
}
