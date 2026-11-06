package dev.client.geology;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.registry.ModBlocks;
import dev.world.geology.CaveAnalyzer;
import dev.world.geology.ScannerUpgrade;
import dev.world.geology.station.SeismicStationBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * Draws the whole 3×3×3 station from its main block. Like the drills, it renders from the level's
 * global list with the structure's bounds so the model does not vanish when the main block alone
 * leaves the view. GeckoLib turns the model by the block's horizontal facing; installed upgrade
 * modules show as extra parts.
 */
public final class SeismicStationRenderer extends GeoBlockRenderer<SeismicStationBlockEntity> {
    public SeismicStationRenderer() {
        super(new SeismicStationModel());
    }

    /**
     * Shows the parts of the installed upgrade levels and hides the rest: scanner modules add their
     * own parts, and every cave analyser level adds its sensors (ground probes, then a sounding
     * mast, then the processing unit) in turn.
     */
    static void showUpgrades(BakedGeoModel model, int level, int analyzer) {
        for (int part = 1; part <= ScannerUpgrade.MAX_LEVEL; part++) {
            boolean hidden = part > level;
            model.getBone("upgrade_" + part).ifPresent(bone -> bone.setHidden(hidden));
        }
        for (int part = 1; part <= CaveAnalyzer.MAX_LEVEL; part++) {
            boolean hidden = part > analyzer;
            model.getBone("analyzer_" + part).ifPresent(bone -> bone.setHidden(hidden));
        }
    }

    @Override
    public void preRender(
            PoseStack poseStack,
            SeismicStationBlockEntity animatable,
            BakedGeoModel model,
            MultiBufferSource bufferSource,
            VertexConsumer buffer,
            boolean isReRender,
            float partialTick,
            int packedLight,
            int packedOverlay,
            int colour) {
        // The baked model is shared by every station, so each one sets the parts before drawing.
        showUpgrades(model, animatable.scannerLevel(), animatable.analyzerLevel());
        super.preRender(
                poseStack,
                animatable,
                model,
                bufferSource,
                buffer,
                isReRender,
                partialTick,
                packedLight,
                packedOverlay,
                colour);
    }

    @Override
    public boolean shouldRenderOffScreen(SeismicStationBlockEntity blockEntity) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(SeismicStationBlockEntity blockEntity) {
        return ModBlocks.SEISMIC_STATION_STRUCTURE.bounds(blockEntity.getBlockPos()).inflate(1.0D);
    }
}
