package dev.mixin;

import dev.config.OreOverrides;
import dev.config.OreDimensionOverrides;
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

    @Unique
    private List<String> oresAndDrills$oreOverrides = List.of();
    @Unique
    private List<String> oresAndDrills$oreDimensionOverrides = List.of();

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
}
