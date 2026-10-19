package dev.config;

import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class OreDepositConfig {
    
    // EN: Hard lower limit for the distance between large deposits, in blocks.
    // RU: Жёсткая нижняя граница расстояния между крупными залежами, в блоках.
    public static final int MIN_LARGE_DEPOSIT_SPACING_LIMIT = 50;

    // EN: Technical upper limit for the distance between large deposits: the world border radius, so the
    //     value is effectively unbounded while chunk/cell arithmetic cannot overflow.
    // RU: Технический верхний предел расстояния между крупными залежами: радиус границы мира. Практически
    //     без ограничений, но вычисления по чанкам и ячейкам не переполняются.
    public static final int MAX_LARGE_DEPOSIT_SPACING_LIMIT = 30_000_000;

    // EN: Default distances between large deposits, in blocks.
    // RU: Расстояния между крупными залежами по умолчанию, в блоках.
    public static final int DEFAULT_MIN_LARGE_DEPOSIT_SPACING = 200;
    public static final int DEFAULT_MAX_LARGE_DEPOSIT_SPACING = 600;

    // EN: Maximum deposit-generation attempts per chunk; prevents lag spikes.
    // RU: Максимальное число попыток генерации залежей на чанк; защита от лагов.
    public static final double MAX_GENERATION_ATTEMPTS_PER_CHUNK = 8.0D;
   
    // EN: Absolute minimum number of blocks in one deposit.
    // RU: Абсолютное минимальное число блоков в залежи.
    public static final int MIN_DEPOSIT_BLOCKS = 1;
  
    // EN: Absolute maximum number of blocks in one deposit; protects chunk data and world generation.
    // RU: Абсолютное максимальное число блоков в залежи; защита данных чанка и генерации.
    public static final int MAX_DEPOSIT_BLOCKS = 720;

 
    // EN: How much larger deposit tiers are rarer; a higher value means fewer large deposits.
    // RU: Насколько более крупные типы залежей встречаются реже: больше число — реже крупные залежи.
    public static final double LARGER_TIER_RARITY = 3.0D;

    // EN: How much deposit size affects rarity; a higher value makes large deposits rarer.
    // RU: Влияние размера залежи на её редкость: больше число — большие залежи реже.
    public static final double DEPOSIT_SIZE_RARITY_WEIGHT = 0.5D;

    // EN: How much total ore reserve affects rarity; a higher value makes rich deposits rarer.
    // RU: Влияние общего запаса руды на редкость: больше число — богатые залежи реже.
    public static final double ORE_AMOUNT_RARITY_WEIGHT = 0.5D;

    // EN: Multiplier for MEDIUM/LARGE generation attempts; 1.0 keeps the original frequency.
    // RU: Общий множитель попыток создать MEDIUM/LARGE залежь: 1.0 оставляет исходную частоту.
    public static final double LARGE_DEPOSIT_ATTEMPT_MULTIPLIER = 1.0D;

    // EN: Retention of TINY/SMALL candidates of datapack "frequency" rules; 0.5 halves them, 1.0 keeps all.
    //     Automatic TINY/SMALL use SMALL_DEPOSIT_ATTEMPT_RETENTION instead.
    // RU: Доля кандидатов TINY/SMALL у правил датапака с "frequency": 0.5 оставляет половину, 1.0 — все.
    //     Автоматические TINY/SMALL используют SMALL_DEPOSIT_ATTEMPT_RETENTION.
    public static final double TINY_SMALL_SPAWN_RETENTION = 1.0D;

    // EN: Share of original ore placed-feature attempts kept as TINY/SMALL candidates in the regular mode.
    //     Not applied to "vanilla-like" generation. Reduced 32x from the initial 0.35; subject to playtesting.
    // RU: Доля исходных попыток размещения руды, которая становится кандидатами TINY/SMALL в обычном режиме.
    //     На режим «Как в ваниле» не действует. Уменьшено в 32 раза с начальных 0.35; баланс для проверки в игре.
    public static final double SMALL_DEPOSIT_ATTEMPT_RETENTION = 0.0109375D;

    // EN: Share of the kept attempts that become TINY; the rest (1 - share) become SMALL.
    // RU: Доля сохранённых попыток, которая становится TINY; остальные (1 - доля) становятся SMALL.
    public static final double SMALL_DEPOSIT_TINY_SHARE = 0.40D;

    // EN: CAVE_WALL placement: chance that a TINY/SMALL candidate is laid on a cave wall instead of staying buried
    //     at its attempt position, for the most common ore of the dimension and for an ore at least
    //     SMALL_DEPOSIT_WALL_RARITY_DECADES orders of magnitude rarer. In between it follows log10 of the ore's
    //     share of the most common ore's original frequency.
    // RU: Режим CAVE_WALL: шанс, что кандидат TINY/SMALL ляжет на стену пещеры, а не останется в толще породы в
    //     исходной точке, — для самой частой руды измерения и для руды, которая реже её на
    //     SMALL_DEPOSIT_WALL_RARITY_DECADES порядков и больше. Между ними — по log10 доли частоты от самой частой руды.
    public static final double SMALL_DEPOSIT_WALL_CHANCE_COMMON = 0.90D;
    public static final double SMALL_DEPOSIT_WALL_CHANCE_RARE = 0.20D;
    public static final double SMALL_DEPOSIT_WALL_RARITY_DECADES = 2.0D;

    // EN: Whole-deposit reserve range of a TINY deposit at Richness 1, in ore units.
    // RU: Суммарный запас всей залежи TINY при богатстве 1, в единицах руды.
    public static final int TINY_DEPOSIT_MIN_RESERVE = 1;
    public static final int TINY_DEPOSIT_MAX_RESERVE = 4;

    // EN: Whole-deposit reserve range of a SMALL deposit at Richness 1, in ore units.
    // RU: Суммарный запас всей залежи SMALL при богатстве 1, в единицах руды.
    public static final int SMALL_DEPOSIT_MIN_RESERVE = 8;
    public static final int SMALL_DEPOSIT_MAX_RESERVE = 16;

    // EN: Extra frequency multiplier for LARGE deposits only; above 1.0 makes LARGE more common without touching MEDIUM.
    // RU: Дополнительный множитель частоты только для LARGE: больше 1.0 — LARGE чаще, MEDIUM не меняется.
    public static final double LARGE_DEPOSIT_FREQUENCY_MULTIPLIER = 2.0D;

    // EN: Exponential rare-ore penalty of the small-tier material weight tables (debug diagnostics only).
    //     Automatic TINY/SMALL no longer use it: they thin each material's own attempts equally.
    // RU: Экспоненциальный штраф редких руд в таблицах весов маленьких типов (только отладочная диагностика).
    //     Автоматические TINY/SMALL его больше не используют: попытки каждой руды прореживаются одинаково.
    public static final double SMALL_DEPOSIT_RARITY_GROWTH = 0.2D;

    // EN: Cap of the exponential part of that penalty, like ore_mining_max_exponential_multiplier.
    // RU: Потолок экспоненциальной части штрафа, как ore_mining_max_exponential_multiplier.
    public static final double SMALL_DEPOSIT_RARITY_MAX_MULTIPLIER = 20.0D;

    // EN: Chance for a rare ore to become a large deposit; a higher value favors MEDIUM/LARGE deposits.
    // RU: Шанс редкой руды стать крупной залежью: больше число — чаще MEDIUM/LARGE для редких руд.
    public static final double LARGE_DEPOSIT_RARE_ORE_BOOST = 0.75D;

    // EN: Technical minimum ore-selection weight; prevents an ore from reaching zero chance.
    // RU: Технический минимальный вес выбора руды; не даёт руде получить нулевой шанс.
    public static final double MIN_ORE_SELECTION_WEIGHT = 0.000001D;

    // EN: Shape of large-deposit spacing distribution; 1.0 is a linear distribution.
    // RU: Форма распределения расстояний между крупными залежами: 1.0 — линейное распределение.
    public static final double LARGE_DEPOSIT_SPACING_CURVE = 0.75D;

    // EN: Random large-deposit spacing variation; 0.03
    // RU: Случайное отклонение расстояния между крупными залежами: 0.03
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
        /** Default: on a nearby cave wall, facing the cave, when one exists; otherwise the attempt position. */
        CAVE_WALL
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("ore_deposits");
        MIN_LARGE_DEPOSIT_SPACING = builder
                .comment("Минимальное расстояние в блоках между крупными залежами MEDIUM/LARGE. Не меньше 50.")
                .defineInRange("minimum_medium_large_spacing", DEFAULT_MIN_LARGE_DEPOSIT_SPACING,
                        MIN_LARGE_DEPOSIT_SPACING_LIMIT, MAX_LARGE_DEPOSIT_SPACING_LIMIT);
        MAX_LARGE_DEPOSIT_SPACING = builder
                .comment("Максимальное расстояние в блоках между крупными залежами MEDIUM/LARGE.",
                        "Сверху не ограничено (технический предел — размер мира). Если оно меньше",
                        "минимального, используется минимальное.")
                .defineInRange("maximum_medium_large_spacing", DEFAULT_MAX_LARGE_DEPOSIT_SPACING,
                        MIN_LARGE_DEPOSIT_SPACING_LIMIT, MAX_LARGE_DEPOSIT_SPACING_LIMIT);
        ORE_MINING_EXPONENTIAL_GROWTH = builder
                .comment("Exponential growth coefficient for manual mining time of concentrated ore deposits.",
                        "0 disables the exponential addition while preserving linear scaling.")
                .defineInRange("ore_mining_exponential_growth", 0.04D, 0.0D, 1.0D);
        ORE_MINING_MAX_EXPONENTIAL_MULTIPLIER = builder
                .comment("Maximum additional exponential multiplier for manual ore-deposit mining time.",
                        "The linear ore-count multiplier is always applied separately.")
                .defineInRange("ore_mining_max_exponential_multiplier", 4.0D, 1.0D, 100.0D);
        IGNORED_ORES = builder
                .comment("ID блоков руды или теги блоков, которые мод не трогает при генерации мира.",
                        "Игнорируемые руды не превращаются в залежи, не объединяются с рудами других модов",
                        "и не учитываются в редкости: их жилы генерируются как в ванили или как задумал автор мода.",
                        "Совпадение с объединённой рудой материала (например minecraft:iron_ore) игнорирует",
                        "этот материал из всех модов. Теги начинаются с '#', например '#c:ores/uranium'.")
                .defineListAllowEmpty("ignored_ores", List.of(),
                        () -> "minecraft:coal_ore", OreDepositConfig::isValidBlockOrTagId);
        ACTIVE_PRESET = builder
                .comment("Активный пресет настроек руд из datapack.",
                        "Пресеты загружаются из data/<namespace>/ore_settings_presets/<path>.json.")
                .define("active_preset", "ores_and_drills:default", OreDepositConfig::isValidPresetId);
        MOD_PRIORITIES = builder
                .comment("Приоритет модов при объединении одинаковых материалов из нескольких модов.")
                .defineListAllowEmpty("mod_priorities",
                        List.of("minecraft", "alltheores", "create", "mekanism", "thermal", "immersiveengineering", "modern_industrialization"),
                        () -> "minecraft", OreDepositConfig::isValidModId);
        SMALL_DEPOSIT_PLACEMENT = builder
                .comment("Размещение маленьких залежей TINY/SMALL в обычном режиме генерации.",
                        "CAVE_WALL (по умолчанию) — строить у ближайшей стены пещеры, если она есть рядом, чтобы залежь",
                        "была видна; иначе в исходной точке попытки.",
                        "INHERITED — всегда в исходной точке попытки руды: высота, биом и отсечение у воздуха как у исходной жилы.")
                .defineEnum("small_deposit_placement", SmallDepositPlacement.CAVE_WALL);
        builder.pop();
        SPEC = builder.build();
    }

    private OreDepositConfig() {
    }

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
        return new String[] {entry.substring(0, first), entry.substring(first + 1, second),
                entry.substring(second + 1, third), entry.substring(third + 1)};
    }

    private static boolean isValidPresetId(Object value) {
        return value instanceof String id && ResourceLocation.tryParse(id) != null;
    }

    private static boolean isValidModId(Object value) {
        return value instanceof String id && !id.isEmpty();
    }
}
