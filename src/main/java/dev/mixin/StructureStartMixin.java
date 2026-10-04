package dev.mixin;

import dev.world.level.levelgen.DepositWorldgenGuard;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks structure building so deposit protection does not stop structures from being placed. */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @Inject(method = "placeInChunk", at = @At("HEAD"))
    private void oresAndDrills$enterStructure(
            WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
            BoundingBox box, ChunkPos chunkPos, CallbackInfo ci
    ) {
        DepositWorldgenGuard.enterStructure();
    }

    @Inject(method = "placeInChunk", at = @At("RETURN"))
    private void oresAndDrills$exitStructure(
            WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
            BoundingBox box, ChunkPos chunkPos, CallbackInfo ci
    ) {
        DepositWorldgenGuard.exitStructure();
    }
}
