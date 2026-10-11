package dev.recipe;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.Arrays;
import java.util.List;

/** Exact stacks retain component matching; NeoForge ingredients support tags and custom predicates. */
public final class DrillMiningFluid {
    public static final Codec<DrillMiningFluid> CODEC = Codec.xor(FluidStack.CODEC, SizedFluidIngredient.FLAT_CODEC)
            .xmap(DrillMiningFluid::new, requirement -> requirement.value);

    private final Either<FluidStack, SizedFluidIngredient> value;

    private DrillMiningFluid(Either<FluidStack, SizedFluidIngredient> value) {
        this.value = value.mapLeft(FluidStack::copy);
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
        return value.map(exact -> List.of(exact.copy()), ingredient -> Arrays.stream(ingredient.ingredient().getStacks())
                .map(stack -> stack.copyWithAmount(ingredient.amount())).toList());
    }
}
