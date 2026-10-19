package dev.mixin;

import dev.config.OreOverrides;
import dev.config.OreDimensionOverrides;
import dev.config.OreGenerationMode;
import dev.config.OreWorldDisabledOres;
import dev.world.level.storage.OreWorldSettingsHolder;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Stores editable Ore Settings in the root tag of level.dat, alongside vanilla world-generation settings. */
@Mixin(PrimaryLevelData.class)
public abstract class PrimaryLevelDataMixin implements OreWorldSettingsHolder {
    private static final String ORES_AND_DRILLS_SETTINGS = "OresAndDrillsOreSettings";
    private static final String ORE_OVERRIDES = "OreOverrides";
    private static final String ORE_DIMENSION_OVERRIDES = "OreDimensionOverrides";
    private static final String VANILLA_LIKE_GENERATION = "VanillaLikeGeneration";
    private static final String DISABLED_ORES = "DisabledOres";

    @Unique
    private List<String> oresAndDrills$oreOverrides = List.of();
    @Unique
    private List<String> oresAndDrills$oreDimensionOverrides = List.of();
    @Unique
    private boolean oresAndDrills$vanillaLikeGeneration;
    @Unique
    private List<String> oresAndDrills$disabledOres = List.of();

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/LevelSettings;Lnet/minecraft/world/level/levelgen/WorldOptions;Lnet/minecraft/world/level/storage/PrimaryLevelData$SpecialWorldProperty;Lcom/mojang/serialization/Lifecycle;)V",
            at = @At("RETURN")
    )
    private void oresAndDrills$captureNewWorldSettings(
            LevelSettings settings,
            WorldOptions options,
            PrimaryLevelData.SpecialWorldProperty property,
            Lifecycle lifecycle,
            CallbackInfo callbackInfo
    ) {
        oresAndDrills$setOreOverrides(OreOverrides.worldOverrides());
        oresAndDrills$setOreDimensionOverrides(OreDimensionOverrides.encoded());
        oresAndDrills$setVanillaLikeGeneration(OreGenerationMode.vanillaLikeGeneration());
        oresAndDrills$setDisabledOres(OreWorldDisabledOres.entries());
    }

    @Inject(method = "parse", at = @At("RETURN"))
    private static <T> void oresAndDrills$readSettings(
            Dynamic<T> tag,
            LevelSettings settings,
            PrimaryLevelData.SpecialWorldProperty property,
            WorldOptions options,
            Lifecycle lifecycle,
            CallbackInfoReturnable<PrimaryLevelData> callbackInfo
    ) {
        List<String> entries = tag.get(ORES_AND_DRILLS_SETTINGS)
                .get(ORE_OVERRIDES)
                .asStream()
                .flatMap(value -> value.asString().result().stream())
                .toList();
        OreWorldSettingsHolder holder = (OreWorldSettingsHolder)callbackInfo.getReturnValue();
        holder.oresAndDrills$setOreOverrides(entries);
        OreOverrides.setWorldOverrides(entries);
        List<String> dimensionEntries = tag.get(ORES_AND_DRILLS_SETTINGS)
                .get(ORE_DIMENSION_OVERRIDES)
                .asStream()
                .flatMap(value -> value.asString().result().stream())
                .toList();
        holder.oresAndDrills$setOreDimensionOverrides(dimensionEntries);
        OreDimensionOverrides.setDisabled(dimensionEntries);
        // Worlds saved before this setting existed keep the established per-cell TINY/SMALL planner.
        boolean vanillaLike = tag.get(ORES_AND_DRILLS_SETTINGS)
                .get(VANILLA_LIKE_GENERATION)
                .asBoolean(false);
        holder.oresAndDrills$setVanillaLikeGeneration(vanillaLike);
        OreGenerationMode.setVanillaLikeGeneration(vanillaLike);
        List<String> disabledOres = tag.get(ORES_AND_DRILLS_SETTINGS)
                .get(DISABLED_ORES)
                .asStream()
                .flatMap(value -> value.asString().result().stream())
                .toList();
        holder.oresAndDrills$setDisabledOres(disabledOres);
        OreWorldDisabledOres.set(disabledOres);
    }

    @Inject(method = "setTagData", at = @At("TAIL"))
    private void oresAndDrills$writeSettings(
            RegistryAccess registries,
            CompoundTag nbt,
            CompoundTag playerNbt,
            CallbackInfo callbackInfo
    ) {
        CompoundTag settings = new CompoundTag();
        ListTag entries = new ListTag();
        for (String entry : oresAndDrills$oreOverrides) {
            entries.add(StringTag.valueOf(entry));
        }
        settings.put(ORE_OVERRIDES, entries);
        ListTag dimensionEntries = new ListTag();
        for (String entry : oresAndDrills$oreDimensionOverrides) {
            dimensionEntries.add(StringTag.valueOf(entry));
        }
        settings.put(ORE_DIMENSION_OVERRIDES, dimensionEntries);
        settings.putBoolean(VANILLA_LIKE_GENERATION, oresAndDrills$vanillaLikeGeneration);
        ListTag disabledOres = new ListTag();
        for (String entry : oresAndDrills$disabledOres) {
            disabledOres.add(StringTag.valueOf(entry));
        }
        settings.put(DISABLED_ORES, disabledOres);
        nbt.put(ORES_AND_DRILLS_SETTINGS, settings);
    }

    @Override
    public List<String> oresAndDrills$oreOverrides() {
        return oresAndDrills$oreOverrides;
    }

    @Override
    public void oresAndDrills$setOreOverrides(List<String> entries) {
        oresAndDrills$oreOverrides = List.copyOf(entries == null ? List.of() : new ArrayList<>(entries));
    }

    @Override
    public List<String> oresAndDrills$oreDimensionOverrides() {
        return oresAndDrills$oreDimensionOverrides;
    }

    @Override
    public void oresAndDrills$setOreDimensionOverrides(List<String> entries) {
        oresAndDrills$oreDimensionOverrides = List.copyOf(entries == null ? List.of() : new ArrayList<>(entries));
    }

    @Override
    public List<String> oresAndDrills$disabledOres() {
        return oresAndDrills$disabledOres;
    }

    @Override
    public void oresAndDrills$setDisabledOres(List<String> entries) {
        oresAndDrills$disabledOres = List.copyOf(entries == null ? List.of() : new ArrayList<>(entries));
    }

    @Override
    public boolean oresAndDrills$vanillaLikeGeneration() {
        return oresAndDrills$vanillaLikeGeneration;
    }

    @Override
    public void oresAndDrills$setVanillaLikeGeneration(boolean enabled) {
        oresAndDrills$vanillaLikeGeneration = enabled;
    }
}
