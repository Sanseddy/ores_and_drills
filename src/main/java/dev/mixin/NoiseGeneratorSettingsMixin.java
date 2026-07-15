package dev.mixin;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(NoiseGeneratorSettings.class)
public abstract class NoiseGeneratorSettingsMixin {
    @Mutable
    @Shadow
    @Final
    private boolean oreVeinsEnabled;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void factoryExpansion$adjustNoiseSettings(
            NoiseSettings noiseSettings,
            BlockState defaultBlock,
            BlockState defaultFluid,
            NoiseRouter noiseRouter,
            SurfaceRules.RuleSource surfaceRule,
            List<Climate.ParameterPoint> spawnTarget,
            int seaLevel,
            boolean disableMobGeneration,
            boolean aquifersEnabled,
            boolean oreVeinsEnabled,
            boolean useLegacyRandomSource,
            CallbackInfo callbackInfo
    ) {
        this.oreVeinsEnabled = false;
    }
}
