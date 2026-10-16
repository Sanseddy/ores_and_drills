package dev.recipe;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Exact stacks retain component matching; NeoForge ingredients support tags and custom predicates. */
public final class DrillMiningFluid {
    public static final Codec<DrillMiningFluid> CODEC = Codec.xor(FluidStack.CODEC, SizedFluidIngredient.FLAT_CODEC)
            .xmap(DrillMiningFluid::new, requirement -> requirement.value);

    private final Either<FluidStack, SizedFluidIngredient> value;

    private DrillMiningFluid(Either<FluidStack, SizedFluidIngredient> value) {
        this.value = value.mapLeft(FluidStack::copy);
    }

    /** Exact single-option requirement used only by JEI pages expanded from a multi-fluid tag. */
    public static DrillMiningFluid displayVariant(FluidStack stack) {
        return new DrillMiningFluid(Either.left(stack.copy()));
    }

    public int amount() {
        return value.map(FluidStack::getAmount, SizedFluidIngredient::amount);
    }

    public boolean test(FluidStack available) {
        return !available.isEmpty() && available.getAmount() >= amount() && value.map(
                exact -> FluidStack.isSameFluidSameComponents(exact, available),
                ingredient -> ingredient.test(available));
    }

    /** Copies for display only. Mining always tests the actual tank contents against the predicate. */
    public List<FluidStack> displayStacks() {
        List<FluidStack> stacks = rawDisplayStacks();
        // A compatibility tag may expose source, flowing and legacy registry variants under the
        // same localized name. Showing all of them produces duplicate tooltip lines and an apparently
        // repeating JEI tank, even though they communicate the same choice to the player.
        LinkedHashMap<String, FluidStack> visible = new LinkedHashMap<>();
        for (FluidStack stack : stacks) {
            String key = stack.getAmount() + "|" + stack.getHoverName().getString();
            visible.putIfAbsent(key, stack);
        }
        return List.copyOf(visible.values());
    }

    /** True when several registry alternatives all have the same player-facing liquid name. */
    public boolean hasSameNamedAlternatives() {
        List<FluidStack> stacks = rawDisplayStacks();
        return stacks.size() > 1
                && stacks.stream().map(stack -> stack.getHoverName().getString()).distinct().count() == 1;
    }

    private List<FluidStack> rawDisplayStacks() {
        return value.map(exact -> List.of(exact.copy()), ingredient ->
                Arrays.stream(ingredient.ingredient().getStacks())
                        .map(stack -> stack.copyWithAmount(ingredient.amount())).toList());
    }
}
