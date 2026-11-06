package dev.client.geology;

import dev.OresAndDrillsMod;
import dev.world.geology.station.SeismicStationBlockEntity;
import dev.world.geology.station.StationState;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * Station model. The indicator lamps are part of the texture: green while working, amber when idle,
 * dark without power.
 */
public final class SeismicStationModel extends GeoModel<SeismicStationBlockEntity> {
    static final ResourceLocation GEOMETRY = id("geo/seismic_survey_station.geo.json");
    static final ResourceLocation ANIMATIONS =
            id("animations/seismic_survey_station.animation.json");
    static final ResourceLocation IDLE_TEXTURE = id("textures/block/seismic_survey_station.png");
    static final ResourceLocation ACTIVE_TEXTURE =
            id("textures/block/seismic_survey_station_active.png");
    static final ResourceLocation OFF_TEXTURE = id("textures/block/seismic_survey_station_off.png");

    @Override
    public ResourceLocation getModelResource(SeismicStationBlockEntity animatable) {
        return GEOMETRY;
    }

    @Override
    public ResourceLocation getTextureResource(SeismicStationBlockEntity animatable) {
        StationState state = animatable.state();
        if (state == StationState.NO_POWER) {
            return OFF_TEXTURE;
        }
        return state == StationState.IDLE ? IDLE_TEXTURE : ACTIVE_TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(SeismicStationBlockEntity animatable) {
        return ANIMATIONS;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, path);
    }
}
