package dev.client.geology;

import dev.client.map.OreMapColors;
import dev.network.SeismicSurveyMapPayload;
import dev.world.geology.ore.OreIdentification;
import dev.world.geology.ore.OreTraits;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Names, colours and number formats shared by the station and drill screens. */
public final class SurveyText {
    public static final String KEY = "gui.ores_and_drills.seismic.";

    private SurveyText() {}

    public static Component text(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    /** "62 %" in the player's language. */
    public static Component percent(double fraction) {
        return text("percent", Math.round(Math.max(0.0D, Math.min(1.0D, fraction)) * 100.0D));
    }

    public static Component blockName(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return Component.literal(id);
        }
        return BuiltInRegistries.BLOCK
                .getOptional(location)
                .<Component>map(block -> block.getName())
                .orElse(Component.literal(id));
    }

    public static Component category(int ordinal) {
        OreTraits.Category[] values = OreTraits.Category.values();
        OreTraits.Category category =
                ordinal >= 0 && ordinal < values.length
                        ? values[ordinal]
                        : OreTraits.Category.OTHER;
        return text("category." + category.name().toLowerCase(Locale.ROOT));
    }

    /** What a candidate stands for: an ore, an ore class or a kind of natural source. */
    public static Component candidate(int source, int category, String oreId) {
        OreIdentification.Source kind = OreIdentification.Source.byId(source);
        if (kind == OreIdentification.Source.ORE) {
            return oreId == null || oreId.isEmpty() ? category(category) : blockName(oreId);
        }
        return text("source." + kind.name().toLowerCase(Locale.ROOT));
    }

    public static Component candidate(SeismicSurveyMapPayload.Candidate candidate) {
        return candidate(candidate.source(), candidate.category(), candidate.oreId());
    }

    /** Opaque RGB colour of a candidate in maps and the 3D view. */
    public static int color(int source, int category, String oreId) {
        OreIdentification.Source kind = OreIdentification.Source.byId(source);
        if (kind == OreIdentification.Source.ORE) {
            ResourceLocation location =
                    oreId == null || oreId.isEmpty() ? null : ResourceLocation.tryParse(oreId);
            if (location != null) {
                return OreMapColors.colorFor(location) & 0xFFFFFF;
            }
            OreTraits.Category[] values = OreTraits.Category.values();
            OreTraits.Category value =
                    category >= 0 && category < values.length
                            ? values[category]
                            : OreTraits.Category.OTHER;
            return switch (value) {
                case METALLIC -> 0xD98A2B;
                case GEM -> 0x3FD0D8;
                case DUST -> 0xD83B3B;
                case OTHER -> 0x9A7B4F;
            };
        }
        return switch (kind) {
            case DENSE_MASSIF -> 0x8C93A6;
            case LOW_VELOCITY_LENS -> 0x6E9C5A;
            case FRACTURED_ZONE -> 0x7A6A8C;
            case LITHOLOGIC_CONTACT -> 0xB0A890;
            case CAVITY -> 0x2D4C8C;
            case NOISE -> 0x6E6E6E;
            case ORE -> 0xD98A2B;
        };
    }

    /** Colour of a target: its most probable explanation, grey when nothing stands out. */
    public static int color(SeismicSurveyMapPayload.Target target) {
        if (target.candidates().isEmpty()) {
            return 0x9A9A9A;
        }
        SeismicSurveyMapPayload.Candidate best = target.candidates().getFirst();
        return color(best.source(), best.category(), best.oreId());
    }

    public static String number(double value, int decimals) {
        return plainSpaces(String.format(locale(), "%." + decimals + "f", value));
    }

    /** Digit groups only from five digits on, as Russian typography wants for "1000". */
    public static String integer(long value) {
        return plainSpaces(
                String.format(locale(), Math.abs(value) >= 10_000 ? "%,d" : "%d", value));
    }

    // The game language, not the system locale, decides the decimal comma: a Russian interface on
    // an English system must still read "3,5".
    private static Locale locale() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return Locale.ROOT;
        }
        return Locale.forLanguageTag(
                minecraft.getLanguageManager().getSelected().replace('_', '-'));
    }

    // The JDK groups Russian digits with no-break spaces, which the default font has no glyph for.
    private static String plainSpaces(String text) {
        return text.replaceAll("\\p{Zs}", " ");
    }
}
