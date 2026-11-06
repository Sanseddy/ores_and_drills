package dev.client.geology;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.registry.ModDataComponents;
import dev.world.geology.station.SeismicStationItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * The station item shows the same model as the placed station, at rest, with the upgrade parts its
 * stored level installs.
 */
public final class SeismicStationItemRenderer extends GeoItemRenderer<SeismicStationItem> {
    public SeismicStationItemRenderer() {
        super(
                new GeoModel<>() {
                    @Override
                    public ResourceLocation getModelResource(SeismicStationItem animatable) {
                        return SeismicStationModel.GEOMETRY;
                    }

                    @Override
                    public ResourceLocation getTextureResource(SeismicStationItem animatable) {
                        return SeismicStationModel.IDLE_TEXTURE;
                    }

                    @Override
                    public ResourceLocation getAnimationResource(SeismicStationItem animatable) {
                        return SeismicStationModel.ANIMATIONS;
                    }
                });
    }

    @Override
    public void preRender(
            PoseStack poseStack,
            SeismicStationItem animatable,
            BakedGeoModel model,
            MultiBufferSource bufferSource,
            VertexConsumer buffer,
            boolean isReRender,
            float partialTick,
            int packedLight,
            int packedOverlay,
            int colour) {
        Integer level =
                currentItemStack == null
                        ? null
                        : currentItemStack.get(ModDataComponents.SCANNER_LEVEL.get());
        Integer analyzer =
                currentItemStack == null
                        ? null
                        : currentItemStack.get(ModDataComponents.ANALYZER_LEVEL.get());
        SeismicStationRenderer.showUpgrades(
                model, level == null ? 0 : level, analyzer == null ? 0 : analyzer);
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
}
