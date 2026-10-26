package dev.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.SortedSet;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("destructionProgress")
    Long2ObjectMap<SortedSet<BlockDestructionProgress>> oresAndDrills$destructionProgress();
}
