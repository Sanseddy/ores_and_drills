package dev.world.block.entity.drill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.fluids.FluidStack;

public final class PreviewGrid {
    private final SimpleContainer container;

    public PreviewGrid(int visibleSlots) {
        this.container = new SimpleContainer(Math.max(1, visibleSlots));
    }

    public SimpleContainer container() {
        return container;
    }

    public boolean update(List<OreScanner.Target> targets) {
        boolean changed = false;
        List<PreviewEntry> previews = new ArrayList<>();

        for (OreScanner.Target target : targets) {
            ItemStack preview = target.result().copy();
            if (preview.isEmpty()) {
                continue;
            }

            preview.setCount(1);
            PreviewEntry existing = findExisting(previews, preview);
            if (existing != null) {
                existing.include(
                        target.minCount(),
                        target.maxCount(),
                        target.remainingInVein(),
                        target.requiredFluid());
            } else {
                PreviewEntry entry =
                        new PreviewEntry(
                                preview,
                                target.minCount(),
                                target.maxCount(),
                                target.remainingInVein(),
                                target.requiredFluid());
                previews.add(entry);
            }
        }

        previews.sort(Comparator.comparing(PreviewEntry::sortKey));
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack preview =
                    slot >= previews.size()
                            ? ItemStack.EMPTY
                            : previews.get(slot).stackWithTooltip();
            if (!ItemStack.matches(container.getItem(slot), preview)) {
                container.setItem(slot, preview.copy());
                changed = true;
            }
        }

        return changed;
    }

    private static PreviewEntry findExisting(List<PreviewEntry> shownPreviews, ItemStack preview) {
        for (PreviewEntry shownPreview : shownPreviews) {
            if (shownPreview.isSamePreview(preview)) {
                return shownPreview;
            }
        }

        return null;
    }

    private static final class PreviewEntry {
        private final ItemStack stack;
        private int minCount;
        private int maxCount;
        private double totalAverageLoot;
        private final List<FluidStack> requiredFluids = new ArrayList<>();
        private boolean acceptsSameNamedFluidAlternatives;

        private PreviewEntry(
                ItemStack stack,
                int minCount,
                int maxCount,
                int remainingInVein,
                dev.recipe.DrillMiningFluid requiredFluid) {
            this.stack = stack;
            this.minCount = Math.max(1, minCount);
            this.maxCount = Math.max(this.minCount, maxCount);
            includeTotalLoot(this.minCount, this.maxCount, remainingInVein);
            includeFluid(requiredFluid);
        }

        private boolean isSamePreview(ItemStack other) {
            return ItemStack.isSameItemSameComponents(stack, other);
        }

        private String sortKey() {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }

        private void include(
                int minCount,
                int maxCount,
                int remainingInVein,
                dev.recipe.DrillMiningFluid requiredFluid) {
            int clampedMin = Math.max(1, minCount);
            int clampedMax = Math.max(clampedMin, maxCount);
            this.minCount = Math.min(this.minCount, clampedMin);
            this.maxCount = Math.max(this.maxCount, clampedMax);
            includeTotalLoot(clampedMin, clampedMax, remainingInVein);
            includeFluid(requiredFluid);
        }

        private void includeFluid(dev.recipe.DrillMiningFluid requiredFluid) {
            if (requiredFluid == null) return;
            acceptsSameNamedFluidAlternatives |= requiredFluid.hasSameNamedAlternatives();
            // A fluid tag (e.g. c:sulfuric_acid) is one requirement even when several mods fill it
            // with differently named liquids, so only its first display stack names it in the
            // tooltip.
            List<FluidStack> stacks = requiredFluid.displayStacks();
            if (stacks.isEmpty()) return;
            FluidStack fluid = stacks.getFirst();
            boolean duplicate =
                    requiredFluids.stream().anyMatch(existing -> sameRequirement(existing, fluid));
            if (!duplicate) requiredFluids.add(fluid.copy());
        }

        /** Same amount, and either the same fluid or a liquid with the same displayed name. */
        private static boolean sameRequirement(FluidStack existing, FluidStack fluid) {
            return existing.getAmount() == fluid.getAmount()
                    && (FluidStack.isSameFluidSameComponents(existing, fluid)
                            || existing.getHoverName()
                                    .getString()
                                    .equals(fluid.getHoverName().getString()));
        }

        private void includeTotalLoot(int minCount, int maxCount, int remainingInVein) {
            int remaining = Math.max(0, remainingInVein);
            totalAverageLoot += remaining * ((minCount + maxCount) / 2.0D);
        }

        private ItemStack stackWithTooltip() {
            ItemStack result = stack.copy();
            result.setCount(1);
            List<Component> lore = new ArrayList<>();
            lore.add(
                    Component.translatable(
                                    "tooltip.ores_and_drills.mining_drill.expected_ore",
                                    totalLootText())
                            .withStyle(
                                    style ->
                                            style.withColor(ChatFormatting.GRAY)
                                                    .withItalic(false)));
            // Lore lines can't contain line breaks (they render as an "LF" glyph), so the heading
            // and each fluid are separate lines; translations stay client-side for dedicated
            // servers.
            if (!requiredFluids.isEmpty()) {
                lore.add(
                        Component.translatable(
                                        "tooltip.ores_and_drills.mining_drill.required_fluid")
                                .withStyle(
                                        style ->
                                                style.withColor(ChatFormatting.GOLD)
                                                        .withItalic(false)));
            }
            for (FluidStack fluid : requiredFluids) {
                lore.add(
                        Component.translatable(
                                        acceptsSameNamedFluidAlternatives
                                                ? "recipe.ores_and_drills.any_fluid"
                                                : "tooltip.ores_and_drills.mining_drill.required_fluid_entry",
                                        acceptsSameNamedFluidAlternatives
                                                ? new Object[] {fluid.getHoverName()}
                                                : new Object[] {
                                                    fluid.getHoverName(), fluid.getAmount()
                                                })
                                .withStyle(
                                        style ->
                                                style.withColor(ChatFormatting.GOLD)
                                                        .withItalic(false)));
            }
            result.set(DataComponents.LORE, new ItemLore(lore));
            return result;
        }

        private String totalLootText() {
            return Long.toString(Math.round(totalAverageLoot));
        }
    }
}
