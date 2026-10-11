package dev.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.registry.ModRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/** Output omission preserves the source ore's loot and the existing one-unit deposit depletion. */
public record DrillMiningRecipe(HolderSet<Block> input, Optional<DrillMiningFluid> fluid, int minimumDrillTier,
                                List<DrillMiningCondition> conditions, int priority, Optional<ItemStack> result)
        implements Recipe<DrillMiningInput> {
    public static final MapCodec<DrillMiningRecipe> CODEC = RecordCodecBuilder.<DrillMiningRecipe>mapCodec(instance -> instance.group(
            RegistryCodecs.homogeneousList(Registries.BLOCK).fieldOf("input").forGetter(DrillMiningRecipe::input),
            DrillMiningFluid.CODEC.optionalFieldOf("fluid").forGetter(DrillMiningRecipe::fluid),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("minimum_drill_tier", 1).forGetter(DrillMiningRecipe::minimumDrillTier),
            DrillMiningCondition.CODEC.listOf().optionalFieldOf("conditions", List.of()).forGetter(DrillMiningRecipe::conditions),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(DrillMiningRecipe::priority),
            ItemStack.CODEC.optionalFieldOf("result").forGetter(DrillMiningRecipe::result)
    ).apply(instance, DrillMiningRecipe::new)).validate(recipe -> recipe.input().unwrapKey().isEmpty() && recipe.input().size() == 0
            ? DataResult.error(() -> "Mining recipe must specify at least one input block or a tag") : DataResult.success(recipe));

    public DrillMiningRecipe {
        conditions = List.copyOf(conditions);
        result = result.map(ItemStack::copy);
        if (minimumDrillTier < 1
                || result.filter(ItemStack::isEmpty).isPresent()) throw new IllegalArgumentException("Invalid mining recipe");
    }

    public boolean accepts(BlockState state) { return input.contains(state.getBlockHolder()); }
    public boolean requiresFluid() { return fluid.isPresent(); }

    @Override
    public boolean matches(DrillMiningInput input, Level level) {
        return accepts(input.state()) && input.drillTier() >= minimumDrillTier && level instanceof ServerLevel server
                && conditions.stream().allMatch(condition -> condition.test(server, input));
    }

    @Override
    public ItemStack assemble(DrillMiningInput input, HolderLookup.Provider registries) { return getResultItem(registries).copy(); }
    @Override
    public boolean canCraftInDimensions(int width, int height) { return true; }
    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) { return result.map(ItemStack::copy).orElse(ItemStack.EMPTY); }
    @Override
    public boolean isSpecial() { return true; }
    @Override
    public RecipeSerializer<?> getSerializer() { return ModRecipes.DRILL_MINING_SERIALIZER.get(); }
    @Override
    public RecipeType<?> getType() { return ModRecipes.DRILL_MINING.get(); }

    public static final class Serializer implements RecipeSerializer<DrillMiningRecipe> {
        private static final StreamCodec<RegistryFriendlyByteBuf, DrillMiningRecipe> STREAM_CODEC =
                ByteBufCodecs.fromCodecWithRegistries(CODEC.codec());
        @Override
        public MapCodec<DrillMiningRecipe> codec() { return CODEC; }
        @Override
        public StreamCodec<RegistryFriendlyByteBuf, DrillMiningRecipe> streamCodec() { return STREAM_CODEC; }
    }
}
