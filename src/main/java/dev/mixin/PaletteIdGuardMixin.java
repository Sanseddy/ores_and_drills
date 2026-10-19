package dev.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.world.level.chunk.UnregisteredPaletteEntries;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A palette entry without a registry id is written as varint -1, and the client then drops the whole
 * connection ("No value with id -1"). Report the offending entry and send it as id 0 instead. Size and
 * data use the same substitute, so the chunk packet stays consistent.
 */
@Mixin({LinearPalette.class, HashMapPalette.class, SingleValuePalette.class})
public abstract class PaletteIdGuardMixin {
    @WrapOperation(method = {"write", "getSerializedSize"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/IdMap;getId(Ljava/lang/Object;)I"))
    private int oresAndDrills$guardUnregisteredEntry(IdMap<Object> registry, Object value, Operation<Integer> original) {
        int id = original.call(registry, value);
        return id >= 0 ? id : UnregisteredPaletteEntries.substitute(value);
    }
}
