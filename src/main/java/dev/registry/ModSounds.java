package dev.registry;

import dev.OresAndDrillsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(
            Registries.SOUND_EVENT, OresAndDrillsMod.MOD_ID);

    /** Looped while an ultimate drill works; its range comes from {@code attenuation_distance} in sounds.json. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ULTIMATE_MINING_DRILL_WORK = register("block.ultimate_mining_drill.work");

    private ModSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, name);
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
