package dev.world.geology;

import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Upgrade module of the survey equipment. A scanner module raises the seismic scanner level by one
 * (see {@link ScannerUpgrade.Improvement}); a cave analyser module installs or improves the cave
 * analyser (see {@link CaveAnalyzer}). The two kinds are independent and both fit a station and an
 * exploration drill. Installed levels stay with the block when it is broken.
 */
public final class UpgradeModuleItem extends Item {
    private final Kind kind;

    public UpgradeModuleItem(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
    }

    public enum Kind {
        SCANNER,
        CAVE_ANALYZER;

        public int maximum() {
            return this == SCANNER ? ScannerUpgrade.MAX_LEVEL : CaveAnalyzer.MAX_LEVEL;
        }
    }

    public Kind kind() {
        return kind;
    }

    /** Equipment that accepts modules. */
    public interface Upgradeable {
        int scannerLevel();

        /** Raises the level by one; false at the maximum. */
        boolean raiseScannerLevel();

        int analyzerLevel();

        /** Installs or improves the cave analyser; false at the maximum. */
        boolean raiseAnalyzerLevel();
    }

    /** Installs one module from {@code stack} into {@code target}, if it is a module. */
    public static ItemInteractionResult install(
            ItemStack stack, Level level, BlockPos pos, Player player, Upgradeable target) {
        if (!(stack.getItem() instanceof UpgradeModuleItem module)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (target == null || !player.mayBuild()) {
            return ItemInteractionResult.FAIL;
        }
        boolean scanner = module.kind == Kind.SCANNER;
        boolean raised = scanner ? target.raiseScannerLevel() : target.raiseAnalyzerLevel();
        if (!raised) {
            player.displayClientMessage(
                    Component.translatable(
                            scanner
                                    ? "message.ores_and_drills.scanner.max_level"
                                    : "message.ores_and_drills.analyzer.max_level",
                            module.kind.maximum()),
                    true);
            return ItemInteractionResult.CONSUME_PARTIAL;
        }
        stack.consume(1, player);
        level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 1.0F, 0.8F);
        if (scanner) {
            int installed = target.scannerLevel();
            player.displayClientMessage(
                    Component.translatable(
                            "message.ores_and_drills.scanner.upgraded",
                            installed,
                            improvementName(installed)),
                    true);
        } else {
            player.displayClientMessage(
                    Component.translatable(
                            "message.ores_and_drills.analyzer.upgraded",
                            target.analyzerLevel(),
                            CaveAnalyzer.MAX_LEVEL),
                    true);
        }
        return ItemInteractionResult.CONSUME;
    }

    public static Component improvementName(int level) {
        return Component.translatable(
                "gui.ores_and_drills.scanner.improvement."
                        + ScannerUpgrade.improvement(level).name().toLowerCase(Locale.ROOT));
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (kind == Kind.SCANNER) {
            tooltip.add(
                    Component.translatable("tooltip.ores_and_drills.seismic_upgrade_module.desc")
                            .withStyle(ChatFormatting.GRAY));
            for (int level = 1; level <= ScannerUpgrade.MAX_LEVEL; level++) {
                tooltip.add(
                        Component.translatable(
                                        "tooltip.ores_and_drills.seismic_upgrade_module.level",
                                        level,
                                        improvementName(level))
                                .withStyle(ChatFormatting.DARK_GRAY));
            }
            return;
        }
        tooltip.add(
                Component.translatable("tooltip.ores_and_drills.cave_analyzer_module.desc")
                        .withStyle(ChatFormatting.GRAY));
        for (int level = 1; level <= CaveAnalyzer.MAX_LEVEL; level++) {
            tooltip.add(
                    Component.translatable(
                                    "tooltip.ores_and_drills.cave_analyzer_module.level",
                                    level,
                                    CaveAnalyzer.spacing(level),
                                    CaveAnalyzer.radius(level, Integer.MAX_VALUE))
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
