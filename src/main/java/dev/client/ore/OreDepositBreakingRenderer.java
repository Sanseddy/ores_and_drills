package dev.client.ore;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.OresAndDrillsMod;
import dev.mixin.LevelRendererAccessor;
import dev.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.SortedSet;

/**
 * Draws the block-breaking cracks on ore deposits once more after the translucent chunk layer.
 * <p>
 * Vanilla draws the cracks before that layer and without writing depth, while the deposit model submits its
 * complete visual (opaque base, trace, specks) through the translucent layer, which paints over them. The
 * stage event runs while the translucent layer's target is still bound, so this also lands in the right
 * buffer with Fabulous graphics. The model's breaking shell sits outside every visual layer, so it passes
 * the depth test against them.
 */
@EventBusSubscriber(modid = OresAndDrillsMod.MOD_ID, value = Dist.CLIENT)
public final class OreDepositBreakingRenderer {
    /** Same squared distance vanilla uses for breaking progress. */
    private static final double MAXIMUM_DISTANCE_SQUARED = 1024.0D;

    private OreDepositBreakingRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Long2ObjectMap<SortedSet<BlockDestructionProgress>> progress =
                ((LevelRendererAccessor) event.getLevelRenderer()).oresAndDrills$destructionProgress();
        if (progress.isEmpty()) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource crumbling = minecraft.renderBuffers().crumblingBufferSource();
        PoseStack poseStack = new PoseStack();
        boolean rendered = false;
        for (Long2ObjectMap.Entry<SortedSet<BlockDestructionProgress>> entry : progress.long2ObjectEntrySet()) {
            SortedSet<BlockDestructionProgress> stages = entry.getValue();
            if (stages == null || stages.isEmpty()) {
                continue;
            }
            BlockPos pos = BlockPos.of(entry.getLongKey());
            BlockState state = level.getBlockState(pos);
            if (!state.is(ModBlocks.ORE_DEPOSIT.get()) && !state.is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get())) {
                continue;
            }
            double dx = pos.getX() - camera.x;
            double dy = pos.getY() - camera.y;
            double dz = pos.getZ() - camera.z;
            if (dx * dx + dy * dy + dz * dz > MAXIMUM_DISTANCE_SQUARED) {
                continue;
            }

            int stage = Math.max(0, Math.min(ModelBakery.DESTROY_TYPES.size() - 1, stages.last().getProgress()));
            poseStack.pushPose();
            poseStack.translate(dx, dy, dz);
            VertexConsumer consumer = new SheetedDecalTextureGenerator(
                    crumbling.getBuffer(ModelBakery.DESTROY_TYPES.get(stage)), poseStack.last(), 1.0F
            );
            minecraft.getBlockRenderer().renderBreakingTexture(state, pos, level, poseStack, consumer, level.getModelData(pos));
            poseStack.popPose();
            rendered = true;
        }
        if (rendered) {
            crumbling.endBatch();
        }
    }
}
