package dev.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable's one-block sub-level renderer reads only {@code ClientLevel#getModelData}, which contains
 * block-entity data and is empty for attachment-backed models. Normal section compilation follows
 * that call with {@code BakedModel#getModelData}; reproduce the missing step for the single-block path.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.neoforge.platform.SableSubLevelRenderPlatformImpl", remap = false)
public abstract class SableSubLevelRenderPlatformMixin {
    @ModifyExpressionValue(
            method = "tesselateBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getModelData(Lnet/minecraft/core/BlockPos;)Lnet/neoforged/neoforge/client/model/data/ModelData;",
                    remap = true
            ),
            require = 0,
            remap = false
    )
    private ModelData oresAndDrills$resolveAttachmentModelData(
            ModelData original,
            @Local(argsOnly = true) BakedModel model,
            @Local(argsOnly = true) BlockState state,
            @Local(argsOnly = true) BlockPos pos
    ) {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? original : model.getModelData(level, pos, state, original);
    }
}
