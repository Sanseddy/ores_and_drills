package dev.world.item;

import dev.world.block.AbstractDrillBlock;
import dev.world.block.EnergyProfile;
import dev.world.block.MiningDrillTier;
import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.List;

public abstract class AbstractDrillBlockItem extends BlockItem implements GeoItem {
    private static final String TOOLTIP = "tooltip.ores_and_drills.mining_drill.";
    private static final double TICKS_PER_SECOND = 20.0D;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final MiningDrillTier tier;
    @Nullable
    private final EnergyProfile energy;
    private final int productivityPercent;

    /**
     * @param energy              {@code null} for a drill that burns solid fuel instead of FE
     * @param productivityPercent bonus progress per cycle towards an extra ore; 0 when the drill has none
     */
    protected AbstractDrillBlockItem(Block block, Item.Properties properties, MiningDrillTier tier,
                                     @Nullable EnergyProfile energy, int productivityPercent) {
        super(block, properties);
        this.tier = tier;
        this.energy = energy;
        this.productivityPercent = productivityPercent;
    }

    /** Factorio-style tooltip: a short description, then one "Property: value" line per stat. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable(getDescriptionId() + ".desc").withStyle(ChatFormatting.GRAY));

        int size = tier.size();
        int areaSize = size + 2 * miningAreaMargin();
        // Width × length × height of the drill body.
        tooltip.add(stat("dimensions", Component.literal(size + "×" + size + "×" + structureHeight())));
        tooltip.add(stat("area", Component.literal(areaSize + "×" + areaSize)));
        double oresPerSecond = tier.oreUnitsPerCycle() * TICKS_PER_SECOND / tier.miningDuration();
        tooltip.add(stat("speed", Component.translatable(TOOLTIP + "speed.value", number(oresPerSecond))));
        if (energy == null) {
            tooltip.add(stat("fuel", Component.translatable(TOOLTIP + "fuel.value")));
        } else {
            tooltip.add(stat("consumption", Component.translatable(TOOLTIP + "consumption.value", energy.energyPerTick())));
        }
        if (productivityPercent > 0) {
            tooltip.add(stat("productivity", Component.translatable(TOOLTIP + "productivity.value", productivityPercent)));
        }
        if (!tier.representativeTool().isEmpty()) {
            tooltip.add(stat("harvest_level", Component.translatable(tier.representativeTool().getDescriptionId())));
        }
    }

    private int structureHeight() {
        return getBlock() instanceof AbstractDrillBlock drill ? drill.structure().height() : tier.size();
    }

    private int miningAreaMargin() {
        return getBlock() instanceof AbstractDrillBlock drill ? drill.structure().miningAreaMargin() : tier.miningAreaMargin();
    }

    private static Component stat(String key, Component value) {
        return Component.translatable(TOOLTIP + "stat",
                Component.translatable(TOOLTIP + key).withStyle(ChatFormatting.GRAY),
                value.copy().withStyle(ChatFormatting.WHITE));
    }

    /** Whole numbers stay whole (5); fractions use the language's own decimal separator (0,25 / 0.25). */
    private static String number(double value) {
        String text = value == Math.rint(value)
                ? Long.toString((long) value)
                : Double.toString(Math.round(value * 100.0D) / 100.0D);
        return text.replace(".", Language.getInstance().getOrDefault(TOOLTIP + "decimal_separator", "."));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
