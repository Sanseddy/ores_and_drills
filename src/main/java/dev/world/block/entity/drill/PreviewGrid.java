package dev.world.block.entity.drill;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
                existing.include(target.minCount(), target.maxCount(), target.remainingInVein());
            } else {
                PreviewEntry entry = new PreviewEntry(preview, target.minCount(), target.maxCount(), target.remainingInVein());
                previews.add(entry);
            }
        }

        previews.sort(Comparator.comparing(PreviewEntry::sortKey));
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack preview = slot >= previews.size() ? ItemStack.EMPTY : previews.get(slot).stackWithTooltip();
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

        private PreviewEntry(ItemStack stack, int minCount, int maxCount, int remainingInVein) {
            this.stack = stack;
            this.minCount = Math.max(1, minCount);
            this.maxCount = Math.max(this.minCount, maxCount);
            includeTotalLoot(this.minCount, this.maxCount, remainingInVein);
        }

        private boolean isSamePreview(ItemStack other) {
            return ItemStack.isSameItemSameComponents(stack, other);
        }

        private String sortKey() {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }

        private void include(int minCount, int maxCount, int remainingInVein) {
            int clampedMin = Math.max(1, minCount);
            int clampedMax = Math.max(clampedMin, maxCount);
            this.minCount = Math.min(this.minCount, clampedMin);
            this.maxCount = Math.max(this.maxCount, clampedMax);
            includeTotalLoot(clampedMin, clampedMax, remainingInVein);
        }

        private void includeTotalLoot(int minCount, int maxCount, int remainingInVein) {
            int remaining = Math.max(0, remainingInVein);
            totalAverageLoot += remaining * ((minCount + maxCount) / 2.0D);
        }

        private ItemStack stackWithTooltip() {
            ItemStack result = stack.copy();
            result.setCount(1);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.translatable("tooltip.ores_and_drills.mining_drill.expected_ore", totalLootText())
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)));
            result.set(DataComponents.LORE, new ItemLore(lore));
            return result;
        }

        private String totalLootText() {
            return Long.toString(Math.round(totalAverageLoot));
        }
    }
}
