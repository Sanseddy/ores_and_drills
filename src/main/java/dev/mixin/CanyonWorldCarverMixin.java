package dev.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CanyonWorldCarver.class)
public abstract class CanyonWorldCarverMixin {
    private static final float FACTORY_EXPANSION_CARVER_CHANCE_MULTIPLIER = 0.90F;

    @Inject(method = "isStartChunk", at = @At("HEAD"), cancellable = true)
    private void factoryExpansion$slightlyReduceCanyonCarverChance(
            CanyonCarverConfiguration config,
            RandomSource random,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        callbackInfo.setReturnValue(random.nextFloat() <= config.probability * FACTORY_EXPANSION_CARVER_CHANCE_MULTIPLIER);
    }
}
