package dev.client.map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Factorio-style resource tooltip: resource name, patch size, total amount and the amount under the cursor. */
public final class OreMapTooltip {
    private OreMapTooltip() {
    }

    public static List<Component> lines(OreMapPatch patch, DiscoveredOreDeposit.OreMapCell hoveredCell) {
        DiscoveredOreDeposit deposit = patch.deposit();
        List<Component> lines = new ArrayList<>(4);
        lines.add(oreName(patch).copy().withStyle(style -> style.withColor(readableOn(patch.baseColor()))));
        lines.add(Component.translatable("gui.ores_and_drills.ore_map.deposit",
                Component.translatable("gui.ores_and_drills.ore_map.tier." + (deposit.tier() >= 3 ? "large" : "medium"))
        ).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("gui.ores_and_drills.ore_map.amount", OreMapPalette.formatAmount(deposit.remainingOre())));
        if (hoveredCell != null) {
            lines.add(Component.translatable("gui.ores_and_drills.ore_map.tile_amount",
                    OreMapPalette.formatAmount(hoveredCell.remainingOre())).withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    /** Single-line form for maps that only accept a plain title string. */
    public static String title(OreMapPatch patch) {
        StringBuilder title = new StringBuilder();
        for (Component line : lines(patch, null)) {
            if (!title.isEmpty()) {
                title.append(" — ");
            }
            title.append(line.getString());
        }
        return title.toString();
    }

    /** Tooltips are dark; very dark resources such as coal get a light grey name instead of their own colour. */
    private static int readableOn(int rgb) {
        int brightest = Math.max(rgb >> 16 & 255, Math.max(rgb >> 8 & 255, rgb & 255));
        return brightest < 0x60 ? 0xAAAAAA : rgb;
    }

    public static Component oreName(OreMapPatch patch) {
        return BuiltInRegistries.BLOCK.get(patch.oreId()).getName();
    }
}
