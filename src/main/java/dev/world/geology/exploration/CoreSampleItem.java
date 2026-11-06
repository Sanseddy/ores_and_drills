package dev.world.geology.exploration;

import dev.registry.ModDataComponents;
import dev.world.geology.ore.OreTraits;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** A drilled core with its log; worthless as ore, it documents what lies below the hole. */
public final class CoreSampleItem extends Item {
    private static final String TOOLTIP = "tooltip.ores_and_drills.core_sample.";
    private static final int LISTED_INTERVALS = 8;

    public CoreSampleItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BoreholeLog log = stack.get(ModDataComponents.BOREHOLE_LOG);
        if (log == null) {
            tooltip.add(Component.translatable(TOOLTIP + "empty").withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(
                Component.translatable(
                                TOOLTIP + "location",
                                log.top().getX(),
                                log.top().getY(),
                                log.top().getZ(),
                                log.bottomY())
                        .withStyle(ChatFormatting.GRAY));
        if (log.ore().isPresent()) {
            tooltip.add(
                    Component.translatable(TOOLTIP + "ore", log.oreTopY(), log.oreBottomY())
                            .withStyle(ChatFormatting.GOLD));
            tooltip.add(
                    Component.translatable(TOOLTIP + "analysis", log.level())
                            .withStyle(ChatFormatting.GRAY));
            for (BoreholeLog.Analysis candidate : log.analysis()) {
                tooltip.add(
                        Component.translatable(
                                        TOOLTIP + "candidate",
                                        candidateName(candidate),
                                        Math.round(candidate.probability() * 100.0F))
                                .withStyle(ChatFormatting.YELLOW));
            }
            tooltip.add(
                    Component.translatable(
                                    TOOLTIP + "estimate",
                                    log.localUnits(),
                                    ExplorationDrillBlockEntity.ESTIMATE_RADIUS)
                            .withStyle(ChatFormatting.YELLOW));
            tooltip.add(
                    Component.translatable(TOOLTIP + (log.industrial() ? "industrial" : "minor"))
                            .withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable(TOOLTIP + "dry").withStyle(ChatFormatting.GRAY));
        }
        if (!log.signals().isEmpty()) {
            tooltip.add(
                    Component.translatable(
                                    TOOLTIP + "signals",
                                    log.signals().stream()
                                            .limit(6)
                                            .map(String::valueOf)
                                            .reduce((a, b) -> a + ", " + b)
                                            .orElse(""))
                            .withStyle(ChatFormatting.DARK_AQUA));
        }
        int shown = 0;
        for (BoreholeLog.Interval interval : log.intervals()) {
            if (shown++ >= LISTED_INTERVALS) {
                tooltip.add(Component.literal("…").withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            // The core analysis, not the block itself, says what an ore interval holds.
            Component name =
                    switch (interval.kind()) {
                        case OPEN -> Component.translatable(TOOLTIP + "open");
                        case FLUID -> Component.translatable(TOOLTIP + "fluid");
                        case ORE -> Component.translatable(TOOLTIP + "ore_interval");
                        case ROCK -> blockName(interval.block());
                    };
            tooltip.add(
                    Component.translatable(
                                    TOOLTIP + "interval", interval.topY(), interval.bottomY(), name)
                            .withStyle(
                                    interval.kind() == BoreholeLog.Kind.ORE
                                            ? ChatFormatting.GOLD
                                            : ChatFormatting.DARK_GRAY));
        }
    }

    private static Component candidateName(BoreholeLog.Analysis candidate) {
        if (!candidate.oreId().isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(candidate.oreId());
            if (id != null) {
                return blockName(id);
            }
        }
        OreTraits.Category[] categories = OreTraits.Category.values();
        OreTraits.Category category =
                candidate.category() >= 0 && candidate.category() < categories.length
                        ? categories[candidate.category()]
                        : OreTraits.Category.OTHER;
        return Component.translatable(
                "gui.ores_and_drills.seismic.category."
                        + category.name().toLowerCase(java.util.Locale.ROOT));
    }

    static Component blockName(ResourceLocation id) {
        return BuiltInRegistries.BLOCK
                .getOptional(id)
                .<Component>map(block -> block.getName())
                .orElse(Component.literal(id.toString()));
    }
}
