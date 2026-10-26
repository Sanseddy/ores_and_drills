package dev.config;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class OreDepositConfig {

    /** Hard lower limit for the distance between MEDIUM/LARGE deposits, in blocks. */
    public static final int MIN_LARGE_DEPOSIT_SPACING_LIMIT = 50;

    /**
     * Upper limit for the distance between MEDIUM/LARGE deposits: the world border radius. The
     * value is effectively unbounded, but chunk and cell arithmetic cannot overflow.
     */
    public static final int MAX_LARGE_DEPOSIT_SPACING_LIMIT = 30_000_000;

    /** Default distances between MEDIUM/LARGE deposits, in blocks. */
    public static final int DEFAULT_MIN_LARGE_DEPOSIT_SPACING = 200;

    public static final int DEFAULT_MAX_LARGE_DEPOSIT_SPACING = 600;

    /** Maximum deposit-generation attempts per chunk; prevents lag spikes. */
    public static final double MAX_GENERATION_ATTEMPTS_PER_CHUNK = 8.0D;

    /** Minimum number of blocks in one deposit. */
    public static final int MIN_DEPOSIT_BLOCKS = 1;

    /** Maximum number of blocks in one deposit; protects chunk data and world generation. */
    public static final int MAX_DEPOSIT_BLOCKS = 720;

    /** How much rarer each larger tier is; a higher value means fewer large deposits. */
    public static final double LARGER_TIER_RARITY = 3.0D;

    /** How much deposit size affects rarity; a higher value makes large deposits rarer. */
    public static final double DEPOSIT_SIZE_RARITY_WEIGHT = 0.5D;

    /** How much the total ore reserve affects rarity; a higher value makes rich deposits rarer. */
    public static final double ORE_AMOUNT_RARITY_WEIGHT = 0.5D;

    /** Multiplier for MEDIUM/LARGE generation attempts; 1.0 keeps the original frequency. */
    public static final double LARGE_DEPOSIT_ATTEMPT_MULTIPLIER = 1.0D;

    /**
     * Share of TINY/SMALL candidates kept for datapack {@code frequency} rules: 0.5 halves them,
     * 1.0 keeps all. Automatic TINY/SMALL use {@link #SMALL_DEPOSIT_ATTEMPT_RETENTION} instead.
     */
    public static final double TINY_SMALL_SPAWN_RETENTION = 1.0D;

    /**
     * Share of original ore placed-feature attempts that become TINY/SMALL candidates in the
     * regular mode. Not applied to "vanilla-like" generation. A balance value that still needs
     * playtesting.
     */
    public static final double SMALL_DEPOSIT_ATTEMPT_RETENTION = 0.0109375D;

    /** Share of the kept attempts that become TINY; the rest become SMALL. */
    public static final double SMALL_DEPOSIT_TINY_SHARE = 0.40D;

    /**
     * CAVE_WALL placement: chance that a TINY/SMALL candidate is laid on a cave wall instead of
     * staying buried at its attempt position. {@code COMMON} applies to the most common ore of the
     * dimension, {@code RARE} to an ore at least {@code RARITY_DECADES} orders of magnitude rarer;
     * in between the chance follows log10 of the ore's share of the most common ore's frequency.
     */
    public static final double SMALL_DEPOSIT_WALL_CHANCE_COMMON = 0.90D;

    public static final double SMALL_DEPOSIT_WALL_CHANCE_RARE = 0.20D;
    public static final double SMALL_DEPOSIT_WALL_RARITY_DECADES = 2.0D;

    /** Whole-deposit reserve range of a TINY deposit at Richness 1, in ore units. */
    public static final int TINY_DEPOSIT_MIN_RESERVE = 1;

    public static final int TINY_DEPOSIT_MAX_RESERVE = 4;

    /** Whole-deposit reserve range of a SMALL deposit at Richness 1, in ore units. */
    public static final int SMALL_DEPOSIT_MIN_RESERVE = 8;

    public static final int SMALL_DEPOSIT_MAX_RESERVE = 16;

    /** Extra frequency multiplier for LARGE only; above 1.0 makes LARGE more common. */
    public static final double LARGE_DEPOSIT_FREQUENCY_MULTIPLIER = 2.0D;

    /**
     * Exponential rare-ore penalty of the small-tier material weight tables, used only by debug
     * diagnostics. Automatic TINY/SMALL thin each material's own attempts equally instead.
     */
    public static final double SMALL_DEPOSIT_RARITY_GROWTH = 0.2D;

    /** Cap of the exponential part of that penalty. */
    public static final double SMALL_DEPOSIT_RARITY_MAX_MULTIPLIER = 20.0D;

    /** Chance for a rare ore to become a MEDIUM/LARGE deposit; higher favors large deposits. */
    public static final double LARGE_DEPOSIT_RARE_ORE_BOOST = 0.75D;

    /** Minimum ore-selection weight; keeps every ore's chance above zero. */
    public static final double MIN_ORE_SELECTION_WEIGHT = 0.000001D;

    /** Shape of the MEDIUM/LARGE spacing distribution; 1.0 is linear. */
    public static final double LARGE_DEPOSIT_SPACING_CURVE = 0.75D;

    /** Random variation of MEDIUM/LARGE spacing. */
    public static final double LARGE_DEPOSIT_SPACING_VARIATION = 0.03D;

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MIN_LARGE_DEPOSIT_SPACING;
    public static final ModConfigSpec.IntValue MAX_LARGE_DEPOSIT_SPACING;
    public static final ModConfigSpec.DoubleValue ORE_MINING_EXPONENTIAL_GROWTH;
    public static final ModConfigSpec.DoubleValue ORE_MINING_MAX_EXPONENTIAL_MULTIPLIER;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> IGNORED_ORES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MOD_PRIORITIES;
    public static final ModConfigSpec.ConfigValue<String> ACTIVE_PRESET;
    public static final ModConfigSpec.EnumValue<SmallDepositPlacement> SMALL_DEPOSIT_PLACEMENT;

    /** Where a retained TINY/SMALL candidate builds its vein. */
    public enum SmallDepositPlacement {
        /** At the original attempt position: inherited height, biome and air-exposure behaviour. */
        INHERITED,
        /**
         * Default: on a nearby cave wall, facing the cave, when one exists; otherwise the attempt
         * position.
         */
        CAVE_WALL
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("ore_deposits");
        MIN_LARGE_DEPOSIT_SPACING =
                builder.comment(
                                "Минимальное расстояние в блоках между крупными залежами"
                                        + " MEDIUM/LARGE. Не меньше 50.")
                        .defineInRange(
                                "minimum_medium_large_spacing",
                                DEFAULT_MIN_LARGE_DEPOSIT_SPACING,
                                MIN_LARGE_DEPOSIT_SPACING_LIMIT,
                                MAX_LARGE_DEPOSIT_SPACING_LIMIT);
        MAX_LARGE_DEPOSIT_SPACING =
                builder.comment(
                                "Максимальное расстояние в блоках между крупными залежами"
                                        + " MEDIUM/LARGE.",
                                "Сверху не ограничено (технический предел — размер мира). Если оно"
                                        + " меньше",
                                "минимального, используется минимальное.")
                        .defineInRange(
                                "maximum_medium_large_spacing",
                                DEFAULT_MAX_LARGE_DEPOSIT_SPACING,
                                MIN_LARGE_DEPOSIT_SPACING_LIMIT,
                                MAX_LARGE_DEPOSIT_SPACING_LIMIT);
        ORE_MINING_EXPONENTIAL_GROWTH =
                builder.comment(
                                "Exponential growth coefficient for manual mining time of"
                                        + " concentrated ore deposits.",
                                "0 disables the exponential addition while preserving linear"
                                        + " scaling.")
                        .defineInRange("ore_mining_exponential_growth", 0.04D, 0.0D, 1.0D);
        ORE_MINING_MAX_EXPONENTIAL_MULTIPLIER =
                builder.comment(
                                "Maximum additional exponential multiplier for manual ore-deposit"
                                        + " mining time.",
                                "The linear ore-count multiplier is always applied separately.")
                        .defineInRange("ore_mining_max_exponential_multiplier", 4.0D, 1.0D, 100.0D);
        IGNORED_ORES =
                builder.comment(
                                "ID блоков руды или теги блоков, которые мод не трогает при"
                                        + " генерации мира.",
                                "Игнорируемые руды не превращаются в залежи, не объединяются с"
                                        + " рудами других модов",
                                "и не учитываются в редкости: их жилы генерируются как в ванили или"
                                        + " как задумал автор мода.",
                                "Совпадение с объединённой рудой материала (например"
                                        + " minecraft:iron_ore) игнорирует",
                                "этот материал из всех модов. Теги начинаются с '#', например"
                                        + " '#c:ores/uranium'.")
                        .defineListAllowEmpty(
                                "ignored_ores",
                                List.of(),
                                () -> "minecraft:coal_ore",
                                OreDepositConfig::isValidBlockOrTagId);
        ACTIVE_PRESET =
                builder.comment(
                                "Активный пресет настроек руд из datapack.",
                                "Пресеты загружаются из"
                                        + " data/<namespace>/ore_settings_presets/<path>.json.")
                        .define(
                                "active_preset",
                                "ores_and_drills:default",
                                OreDepositConfig::isValidPresetId);
        MOD_PRIORITIES =
                builder.comment(
                                "Приоритет модов при объединении одинаковых материалов из"
                                        + " нескольких модов.")
                        .defineListAllowEmpty(
                                "mod_priorities",
                                List.of(
                                        "minecraft",
                                        "alltheores",
                                        "create",
                                        "mekanism",
                                        "thermal",
                                        "immersiveengineering",
                                        "modern_industrialization"),
                                () -> "minecraft",
                                OreDepositConfig::isValidModId);
        SMALL_DEPOSIT_PLACEMENT =
                builder.comment(
                                "Размещение маленьких залежей TINY/SMALL в обычном режиме"
                                        + " генерации.",
                                "CAVE_WALL (по умолчанию) — строить у ближайшей стены пещеры, если"
                                        + " она есть рядом, чтобы залежь",
                                "была видна; иначе в исходной точке попытки.",
                                "INHERITED — всегда в исходной точке попытки руды: высота, биом и"
                                        + " отсечение у воздуха как у исходной жилы.")
                        .defineEnum("small_deposit_placement", SmallDepositPlacement.CAVE_WALL);
        builder.pop();
        SPEC = builder.build();
    }

    private OreDepositConfig() {}

    private static boolean isValidBlockOrTagId(Object value) {
        if (!(value instanceof String id) || id.isEmpty()) {
            return false;
        }
        String location = id.charAt(0) == '#' ? id.substring(1) : id;
        return !location.isEmpty() && ResourceLocation.tryParse(location) != null;
    }

    static String[] splitOverride(String entry) {
        if (entry.indexOf('|') >= 0) {
            return entry.split("\\|", -1);
        }
        int third = entry.lastIndexOf(':');
        int second = third < 0 ? -1 : entry.lastIndexOf(':', third - 1);
        int first = second < 0 ? -1 : entry.lastIndexOf(':', second - 1);
        if (first < 0) {
            return new String[0];
        }
        return new String[] {
            entry.substring(0, first),
            entry.substring(first + 1, second),
            entry.substring(second + 1, third),
            entry.substring(third + 1)
        };
    }

    private static boolean isValidPresetId(Object value) {
        return value instanceof String id && ResourceLocation.tryParse(id) != null;
    }

    private static boolean isValidModId(Object value) {
        return value instanceof String id && !id.isEmpty();
    }
}
