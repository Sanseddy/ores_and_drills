package dev.mixin;

import dev.world.level.levelgen.GeneratedBlockTags;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Reads the noise settings' base rocks before block tags are loaded; see {@link GeneratedBlockTags}. */
@Mixin(TagManager.class)
public abstract class TagManagerMixin {
    @Shadow
    @Final
    private RegistryAccess registryAccess;

    @Inject(method = "reload", at = @At("HEAD"))
    private void oresAndDrills$captureNoiseBaseStones(
            PreparableReloadListener.PreparationBarrier stage,
            ResourceManager resourceManager,
            ProfilerFiller preparationsProfiler,
            ProfilerFiller reloadProfiler,
            Executor backgroundExecutor,
            Executor gameExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> callbackInfo
    ) {
        GeneratedBlockTags.prepare(this.registryAccess);
    }
}
