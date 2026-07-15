package dev.mixin;

import net.minecraft.world.level.levelgen.NoiseRouterData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(NoiseRouterData.class)
public abstract class NoiseRouterDataMixin {
    private static final double FACTORY_EXPANSION_CAVE_CHEESE_SOLIDITY = 0.34D;

    @ModifyConstant(method = "underground", constant = @Constant(doubleValue = 0.27D))
    private static double factoryExpansion$reduceLargeCheeseCaves(double original) {
        return FACTORY_EXPANSION_CAVE_CHEESE_SOLIDITY;
    }
}
