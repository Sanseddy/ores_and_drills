package dev.world.geology.station;

import dev.config.SeismicSurveyConfig;
import dev.registry.ModDataComponents;
import dev.world.geology.ScannerUpgrade;
import dev.world.geology.SurveyMode;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/** Places a whole seismic station; drawn with the station's own animated model. */
public final class SeismicStationItem extends BlockItem implements GeoItem {
    private static final String TOOLTIP = "tooltip.ores_and_drills.seismic_survey_station.";

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public SeismicStationItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable(TOOLTIP + "desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(line("dimensions", Component.literal("3×3×3")));
        Integer level = stack.get(ModDataComponents.SCANNER_LEVEL);
        int scannerLevel = level == null ? 0 : level;
        tooltip.add(
                line(
                        "level",
                        Component.translatable(
                                TOOLTIP + "level.value", scannerLevel, ScannerUpgrade.MAX_LEVEL)));
        Integer analyzer = stack.get(ModDataComponents.ANALYZER_LEVEL);
        tooltip.add(
                Component.translatable(
                                "tooltip.ores_and_drills.equipment.analyzer",
                                analyzer == null ? 0 : analyzer,
                                dev.world.geology.CaveAnalyzer.MAX_LEVEL)
                        .withStyle(ChatFormatting.DARK_AQUA));
        try {
            tooltip.add(
                    line(
                            "regional",
                            Component.translatable(
                                    TOOLTIP + "radius.value",
                                    SeismicSurveyConfig.radius(SurveyMode.REGIONAL, scannerLevel),
                                    SeismicSurveyConfig.durationTicks(SurveyMode.REGIONAL) / 20)));
            tooltip.add(
                    line(
                            "detailed",
                            Component.translatable(
                                    TOOLTIP + "radius.value",
                                    SeismicSurveyConfig.radius(SurveyMode.DETAILED, scannerLevel),
                                    SeismicSurveyConfig.durationTicks(SurveyMode.DETAILED) / 20)));
            tooltip.add(
                    line(
                            "consumption",
                            Component.translatable(
                                    TOOLTIP + "consumption.value",
                                    SeismicSurveyConfig.energyPerTick(SurveyMode.DETAILED),
                                    SeismicSurveyConfig.energyPerTick(SurveyMode.REGIONAL))));
        } catch (IllegalStateException configNotLoaded) {
            // Tooltips can be built before the config is loaded (e.g. by search indexing).
        }
        if (stack.has(ModDataComponents.STATION_ID)) {
            tooltip.add(
                    Component.translatable(TOOLTIP + "archive")
                            .withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    private static Component line(String key, Component value) {
        return Component.translatable(
                "tooltip.ores_and_drills.mining_drill.stat",
                Component.translatable(TOOLTIP + key).withStyle(ChatFormatting.GRAY),
                value.copy().withStyle(ChatFormatting.WHITE));
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(
                new GeoRenderProvider() {
                    private dev.client.geology.SeismicStationItemRenderer renderer;

                    @Override
                    public dev.client.geology.SeismicStationItemRenderer getGeoItemRenderer() {
                        if (renderer == null) {
                            renderer = new dev.client.geology.SeismicStationItemRenderer();
                        }
                        return renderer;
                    }
                });
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {}

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
