package dev.client.assembly;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.registry.ModAssembly;
import dev.world.assembly.AssemblyBlock;
import dev.world.assembly.AssemblyBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** The actual supplied parts accumulate on the worktop, then give way to the finished machine. */
public final class AssemblyRenderer implements BlockEntityRenderer<AssemblyBlockEntity> {
    public AssemblyRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(
            AssemblyBlockEntity table,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay) {
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(
                Axis.YP.rotationDegrees(
                        270 - table.getBlockState().getValue(AssemblyBlock.FACING).toYRot()));
        ItemStack output = table.inventory().getItem(6);
        var recipe = table.working() ? table.selectedRecipe() : null;
        if (!output.isEmpty()) {
            draw(
                    output,
                    0.95,
                    0.94,
                    0,
                    0.55F,
                    pose,
                    buffers,
                    light,
                    overlay,
                    table,
                    isComponent(output));
        } else if (table.working() && recipe != null) {
            int phase = Math.min(2, table.progress() * 3 / table.duration());
            if (phase == 2) {
                draw(
                        recipe.value().result(),
                        0.95,
                        0.98,
                        0,
                        0.55F,
                        pose,
                        buffers,
                        light,
                        overlay,
                        table,
                        isComponent(recipe.value().result()));
            } else {
                for (int slot = 0;
                        slot < Math.min(phase == 0 ? 2 : 6, recipe.value().parts().size());
                        slot++) {
                    ItemStack part = table.inventory().getItem(slot);
                    draw(
                            part,
                            0.5 + slot % 3 * 0.3,
                            0.91,
                            -0.12 + slot / 3 * 0.24,
                            0.22F,
                            pose,
                            buffers,
                            light,
                            overlay,
                            table,
                            true);
                }
            }
        }
        pose.popPose();
    }

    private void draw(
            ItemStack stack,
            double x,
            double y,
            double z,
            float scale,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay,
            AssemblyBlockEntity table,
            boolean flat) {
        if (stack.isEmpty()) return;
        pose.pushPose();
        pose.translate(x, y, z);
        if (flat) pose.mulPose(Axis.XP.rotationDegrees(-90));
        pose.scale(scale, scale, scale);
        Minecraft.getInstance()
                .getItemRenderer()
                .renderStatic(
                        stack,
                        ItemDisplayContext.FIXED,
                        light,
                        overlay,
                        pose,
                        buffers,
                        table.getLevel(),
                        0);
        pose.popPose();
    }

    private static boolean isComponent(ItemStack stack) {
        return ModAssembly.COMPONENTS.stream().anyMatch(item -> stack.is(item.get()));
    }

    @Override
    public AABB getRenderBoundingBox(AssemblyBlockEntity table) {
        return new AABB(table.getBlockPos())
                .minmax(
                        new AABB(
                                AssemblyBlock.otherPos(table.getBlockPos(), table.getBlockState())))
                .expandTowards(0, 1, 0);
    }
}
