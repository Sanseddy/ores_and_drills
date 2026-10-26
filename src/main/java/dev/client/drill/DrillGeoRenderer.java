package dev.client.drill;

import dev.world.block.AbstractDrillBlock;
import dev.world.block.entity.AbstractDrillBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * The drill model spans the whole multiblock, but only the main block has a block entity. By
 * default a block entity is culled as a unit cube at its position, and together with its chunk
 * section, so the model vanished as soon as the main block left the screen while the rest of the
 * drill was still in view.
 */
public abstract class DrillGeoRenderer<T extends AbstractDrillBlockEntity>
        extends GeoBlockRenderer<T> {
    /** Room for animated parts that swing slightly past the structure's blocks. */
    private static final double MODEL_MARGIN = 1.0D;

    protected DrillGeoRenderer(GeoModel<T> model) {
        super(model);
    }

    /**
     * Rendered from the level's global list, independent of whether the main block's section is
     * visible.
     */
    @Override
    public boolean shouldRenderOffScreen(T blockEntity) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(T blockEntity) {
        BlockState state = blockEntity.getBlockState();
        if (!(state.getBlock() instanceof AbstractDrillBlock drill)
                || !state.hasProperty(AbstractDrillBlock.FACING)) {
            return super.getRenderBoundingBox(blockEntity);
        }
        return drill.structure()
                .bounds(blockEntity.getBlockPos(), state.getValue(AbstractDrillBlock.FACING))
                .inflate(MODEL_MARGIN);
    }
}
