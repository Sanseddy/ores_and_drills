package dev.mixin;

import dev.config.OreOverrides;
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
    private static final String FACTORY_EXPANSION_SETTINGS = "FactoryExpansionOreSettings";
    private static final String ORE_OVERRIDES = "OreOverrides";

    @Unique
    private List<String> factoryExpansion$oreOverrides = List.of();

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/LevelSettings;Lnet/minecraft/world/level/levelgen/WorldOptions;Lnet/minecraft/world/level/storage/PrimaryLevelData$SpecialWorldProperty;Lcom/mojang/serialization/Lifecycle;)V",
            at = @At("RETURN")
    )
    private void factoryExpansion$captureNewWorldSettings(
            LevelSettings settings,
            WorldOptions options,
            PrimaryLevelData.SpecialWorldProperty property,
            Lifecycle lifecycle,
            CallbackInfo callbackInfo
    ) {
        factoryExpansion$setOreOverrides(OreOverrides.worldOverrides());
    }

    @Inject(method = "parse", at = @At("RETURN"))
    private static <T> void factoryExpansion$readSettings(
            Dynamic<T> tag,
            LevelSettings settings,
            PrimaryLevelData.SpecialWorldProperty property,
            WorldOptions options,
            Lifecycle lifecycle,
            CallbackInfoReturnable<PrimaryLevelData> callbackInfo
    ) {
        List<String> entries = tag.get(FACTORY_EXPANSION_SETTINGS)
                .get(ORE_OVERRIDES)
                .asStream()
                .flatMap(value -> value.asString().result().stream())
                .toList();
        OreWorldSettingsHolder holder = (OreWorldSettingsHolder)callbackInfo.getReturnValue();
        holder.factoryExpansion$setOreOverrides(entries);
        OreOverrides.setWorldOverrides(entries);
    }

    @Inject(method = "setTagData", at = @At("TAIL"))
    private void factoryExpansion$writeSettings(
            RegistryAccess registries,
            CompoundTag nbt,
            CompoundTag playerNbt,
            CallbackInfo callbackInfo
    ) {
        CompoundTag settings = new CompoundTag();
        ListTag entries = new ListTag();
        for (String entry : factoryExpansion$oreOverrides) {
            entries.add(StringTag.valueOf(entry));
        }
        settings.put(ORE_OVERRIDES, entries);
        nbt.put(FACTORY_EXPANSION_SETTINGS, settings);
    }

    @Override
    public List<String> factoryExpansion$oreOverrides() {
        return factoryExpansion$oreOverrides;
    }

    @Override
    public void factoryExpansion$setOreOverrides(List<String> entries) {
        factoryExpansion$oreOverrides = List.copyOf(entries == null ? List.of() : new ArrayList<>(entries));
    }
}
