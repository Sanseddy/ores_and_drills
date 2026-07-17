package dev.config;

import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class OreDepositConfig {
    
    // EN: Hard lower limit for the distance between large deposits, in blocks.
    // RU: Жёсткая нижняя граница расстояния между крупными залежами, в блоках.
    public static final int MIN_LARGE_DEPOSIT_SPACING_LIMIT = 200;
   
    // EN: Hard upper limit for the distance between large deposits, in blocks.
    // RU: Жёсткая верхняя граница расстояния между крупными залежами, в блоках.
    public static final int MAX_LARGE_DEPOSIT_SPACING_LIMIT = 600;

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

    // EN: Share of an ore's original frequency allocated to TINY/SMALL deposits.
    // RU: Доля исходной частоты руды, выделяемая маленьким залежам TINY/SMALL.
    public static final double SMALL_DEPOSIT_FREQUENCY_SHARE = 0.65D;

    // EN: Relative frequency of SMALL compared with TINY; a lower value makes SMALL rarer.
    // RU: Относительная частота SMALL по сравнению с TINY: меньше число — реже SMALL.
    public static final double SMALL_TO_TINY_FREQUENCY_RATIO = 0.75D;

    // EN: Minimum combined TINY/SMALL frequency share so rare ores never disappear completely.
    // RU: Минимальная суммарная доля частоты для TINY/SMALL, чтобы редкая руда не исчезла полностью.
    public static final double MIN_SMALL_DEPOSIT_FREQUENCY_SHARE = 0.005D;

    // EN: Base rare-ore penalty in small deposits; a higher value makes rare ores less common.
    // RU: Базовый штраф редких руд в маленьких залежах: больше число — редкие руды встречаются реже.
    public static final double SMALL_DEPOSIT_RARITY_PENALTY = 3.5D;

    // EN: Additional penalty for the very rarest ores in small deposits.
    // RU: Дополнительный штраф для самых редких руд в маленьких залежах.
    public static final double VERY_RARE_ORE_PENALTY = 15.0D;

    // EN: Additional-penalty curve; a higher value targets only the rarest ores more strongly.
    // RU: Кривая дополнительного штрафа: больше число сильнее выделяет только самые редкие руды.
    public static final double VERY_RARE_ORE_PENALTY_CURVE = 1.0D;

    // EN: Penalty fade-out curve from TINY to MEDIUM; a higher value concentrates it on TINY.
    // RU: Кривая ослабления штрафа от TINY к MEDIUM: больше число концентрирует его на TINY.
    public static final double SMALL_DEPOSIT_PENALTY_CURVE = 1.0D;

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
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_DEPOSIT_ORES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MOD_PRIORITIES;
    public static final ModConfigSpec.ConfigValue<String> ACTIVE_PRESET;
    public static final ModConfigSpec.BooleanValue STRICT_WALL_DEPOSIT_LOCATE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("ore_deposits");
        MIN_LARGE_DEPOSIT_SPACING = builder
                .comment("Минимальное расстояние в блоках между крупными залежами MEDIUM/LARGE.")
                .defineInRange("minimum_medium_large_spacing", MIN_LARGE_DEPOSIT_SPACING_LIMIT,
                        MIN_LARGE_DEPOSIT_SPACING_LIMIT, MAX_LARGE_DEPOSIT_SPACING_LIMIT);
        MAX_LARGE_DEPOSIT_SPACING = builder
                .comment("Максимальное расстояние в блоках между крупными залежами MEDIUM/LARGE.")
                .defineInRange("maximum_medium_large_spacing", MAX_LARGE_DEPOSIT_SPACING_LIMIT,
                        MIN_LARGE_DEPOSIT_SPACING_LIMIT, MAX_LARGE_DEPOSIT_SPACING_LIMIT);
        DISABLED_DEPOSIT_ORES = builder
                .comment("ID блоков руды или теги блоков, исключённые из генерации залежей.",
                        "Исключённые руды продолжают генерироваться обычными ванильными жилами.",
                        "Теги начинаются с '#', например '#c:ores/uranium'.")
                .defineListAllowEmpty("disabled_deposit_ores", List.of(),
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
        STRICT_WALL_DEPOSIT_LOCATE = builder
                .comment("Если включено, /locate для TINY/SMALL возвращает только реально созданные",
                        "стеновые или подземные месторождения из ConfirmedDepositIndex и не показывает seed-кандидаты.")
                .define("strict_wall_deposit_locate", false);
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
