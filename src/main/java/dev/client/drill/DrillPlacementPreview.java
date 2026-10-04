package dev.client.drill;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.OresAndDrillsMod;
import dev.world.block.AbstractDrillBlock;
import dev.world.block.AbstractDrillPartBlock;
import dev.world.block.DrillModelCell;
import dev.world.block.DrillStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * While a drill item is held and aimed at a block, shows a translucent ghost of the drill exactly where and how
 * it would be placed (green when placeable, red otherwise) and the ground area its mining covers. Looking at a
 * placed drill shows its mining area.
 */
@EventBusSubscriber(modid = OresAndDrillsMod.MOD_ID, value = Dist.CLIENT)
public final class DrillPlacementPreview {
    private static final float[] VALID_GHOST = {0.35F, 0.95F, 0.45F, 0.35F};
    private static final float[] INVALID_GHOST = {1.0F, 0.3F, 0.3F, 0.35F};
    private static final float[] AREA_FILL = {1.0F, 0.85F, 0.2F, 0.2F};
    private static final float[] AREA_OUTLINE = {1.0F, 0.85F, 0.2F, 0.9F};
    private static final float SURFACE_LIFT = 0.02F;

    private DrillPlacementPreview() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.options.hideGui
                || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof AbstractDrillBlock drill) {
                BlockPlaceContext context = new BlockPlaceContext(player, hand, stack, hit);
                if (context.canPlace()) {
                    render(event, minecraft, level, drill.structure(), context.getClickedPos(),
                            context.getHorizontalDirection(), true);
                }
                return;
            }
        }

        // Looking at a placed drill (its main block or any part) shows the area it mines.
        BlockState state = level.getBlockState(hit.getBlockPos());
        if (state.getBlock() instanceof AbstractDrillBlock drill) {
            render(event, minecraft, level, drill.structure(), hit.getBlockPos(),
                    state.getValue(AbstractDrillBlock.FACING), false);
        } else if (state.getBlock() instanceof AbstractDrillPartBlock part) {
            render(event, minecraft, level, part.structure(), part.structure().originFromPart(hit.getBlockPos(), state),
                    state.getValue(AbstractDrillBlock.FACING), false);
        }
    }

    private static void render(
            RenderLevelStageEvent event,
            Minecraft minecraft,
            ClientLevel level,
            DrillStructure structure,
            BlockPos origin,
            Direction facing,
            boolean placementGhost
    ) {
        float[] ghostColor = !placementGhost || structure.canPlace(level, origin, facing) ? VALID_GHOST : INVALID_GHOST;
        AABB area = structure.miningAreaBounds(origin, facing);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        PoseStack.Pose pose = poseStack.last();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        VertexConsumer fill = buffers.getBuffer(RenderType.debugQuads());
        float surfaceY = (float) area.maxY + SURFACE_LIFT;
        fill.addVertex(pose, (float) area.minX, surfaceY, (float) area.minZ).setColor(AREA_FILL[0], AREA_FILL[1], AREA_FILL[2], AREA_FILL[3]);
        fill.addVertex(pose, (float) area.minX, surfaceY, (float) area.maxZ).setColor(AREA_FILL[0], AREA_FILL[1], AREA_FILL[2], AREA_FILL[3]);
        fill.addVertex(pose, (float) area.maxX, surfaceY, (float) area.maxZ).setColor(AREA_FILL[0], AREA_FILL[1], AREA_FILL[2], AREA_FILL[3]);
        fill.addVertex(pose, (float) area.maxX, surfaceY, (float) area.minZ).setColor(AREA_FILL[0], AREA_FILL[1], AREA_FILL[2], AREA_FILL[3]);
        if (placementGhost) {
            for (int offsetX = 0; offsetX < structure.size(); offsetX++) {
                for (int offsetY = 0; offsetY < structure.height(); offsetY++) {
                    for (int offsetZ = 0; offsetZ < structure.size(); offsetZ++) {
                        BlockPos cellPos = structure.offset(origin, facing, offsetX, offsetY, offsetZ);
                        DrillModelCell cell = structure.exactCellForOffset(facing, offsetX, offsetY, offsetZ);
                        cell.forEachTriangle((first, second, third, normal) ->
                                addTriangle(fill, pose, cellPos, first, second, third, normal, ghostColor));
                    }
                }
            }
        }
        buffers.endBatch(RenderType.debugQuads());

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        AABB areaOutline = new AABB(area.minX, area.maxY, area.minZ, area.maxX, area.maxY + SURFACE_LIFT, area.maxZ);
        LevelRenderer.renderLineBox(poseStack, lines, areaOutline,
                AREA_OUTLINE[0], AREA_OUTLINE[1], AREA_OUTLINE[2], AREA_OUTLINE[3]);
        if (placementGhost) {
            LevelRenderer.renderLineBox(poseStack, lines, structure.bounds(origin, facing),
                    ghostColor[0], ghostColor[1], ghostColor[2], 0.8F);
        }
        buffers.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    /** Emits one model triangle as a degenerate quad, shaded by its facing so the ghost keeps its shape. */
    private static void addTriangle(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            BlockPos cellPos,
            Vec3 first,
            Vec3 second,
            Vec3 third,
            Vec3 normal,
            float[] color
    ) {
        float shade = (float) (0.65D + 0.35D * Math.abs(normal.y) + 0.1D * normal.x);
        float red = Math.min(1.0F, color[0] * shade);
        float green = Math.min(1.0F, color[1] * shade);
        float blue = Math.min(1.0F, color[2] * shade);
        Vec3[] vertices = {first, second, third, third};
        for (Vec3 vertex : vertices) {
            consumer.addVertex(
                    pose,
                    (float) (cellPos.getX() + vertex.x),
                    (float) (cellPos.getY() + vertex.y),
                    (float) (cellPos.getZ() + vertex.z)
            ).setColor(red, green, blue, color[3]);
        }
    }
}
