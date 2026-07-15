package dev.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla-typed access to Create's optional MovementContext. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.behaviour.MovementContext", remap = false)
public interface CreateMovementContextAccessor {
    @Accessor(value = "world", remap = false)
    Level oresAndDrills$getWorld();

    @Accessor(value = "data", remap = false)
    CompoundTag oresAndDrills$getData();

    @Accessor(value = "stall", remap = false)
    void oresAndDrills$setStall(boolean stall);
}
