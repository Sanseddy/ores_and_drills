package dev.registry;

import dev.FactoryExpansionMod;
import dev.world.level.levelgen.OreDepositFeature;
import dev.world.level.levelgen.ReplaceOreFeaturesBiomeModifier;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModWorldgen {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, FactoryExpansionMod.MOD_ID);
    public static final DeferredRegister<MapCodec<? extends BiomeModifier>> BIOME_MODIFIER_SERIALIZERS = DeferredRegister.create(
            NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS,
            FactoryExpansionMod.MOD_ID
    );

    public static final DeferredHolder<Feature<?>, OreDepositFeature> ORE_DEPOSIT_FEATURE = FEATURES.register(
            "ore_deposit",
            () -> new OreDepositFeature(OreDepositFeature.Configuration.CODEC)
    );

    public static final DeferredHolder<MapCodec<? extends BiomeModifier>, MapCodec<ReplaceOreFeaturesBiomeModifier>> REPLACE_ORE_FEATURES =
            BIOME_MODIFIER_SERIALIZERS.register(
                    "replace_ore_features",
                    () -> MapCodec.unit(ReplaceOreFeaturesBiomeModifier.INSTANCE)
            );

    private ModWorldgen() {
    }
}
