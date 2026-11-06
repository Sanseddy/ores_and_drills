package dev.client.geology.lidar;

import dev.client.geology.SurveyText;
import dev.world.geology.CaveAnalysis;
import dev.world.geology.SurveyTarget;
import dev.world.geology.lidar.LidarExtraction;
import dev.world.geology.lidar.LidarObject;
import dev.world.geology.ore.OreIdentification;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * Names and colours of map objects. A target is named after an ore only when that ore is the likely
 * explanation; when ore is likely but no single ore stands out it is an unknown deposit, and when
 * nothing stands out it is a geological anomaly — the scanner never claims more than its
 * probabilities support.
 */
public final class LidarNames {
    /** Probability from which the leading explanation names the object. */
    public static final double NAMING_PROBABILITY = 0.5D;

    private LidarNames() {}

    public static Component name(LidarObject object) {
        return switch (object.kind()) {
            case TARGET -> targetName(object);
            case CAVE ->
                    text(
                            "name.cave."
                                    + CaveAnalysis.Shape.byId(object.shape())
                                            .name()
                                            .toLowerCase(Locale.ROOT));
            case CORE_ORE ->
                    object.candidates().isEmpty()
                            ? text("name.core_ore_unknown")
                            : text("name.core_ore", candidateName(object.candidates().getFirst()));
            case CORE_VOID -> text("name.core_void");
        };
    }

    private static Component targetName(LidarObject object) {
        if (object.candidates().isEmpty()) {
            return text("name.anomaly");
        }
        SurveyTarget.Candidate best = object.candidates().getFirst();
        double ore = oreProbability(object);
        if (best.source() == OreIdentification.Source.ORE
                && best.probability() >= NAMING_PROBABILITY) {
            return best.oreId().isEmpty()
                    ? text("name.class_deposit", SurveyText.category(categoryOf(best)))
                    : text("name.deposit", SurveyText.blockName(best.oreId()));
        }
        if (ore >= NAMING_PROBABILITY) {
            return text("name.unknown_deposit");
        }
        if (best.source() != OreIdentification.Source.ORE
                && best.source() != OreIdentification.Source.NOISE
                && best.probability() >= NAMING_PROBABILITY) {
            return candidateName(best);
        }
        return text("name.anomaly");
    }

    public static double oreProbability(LidarObject object) {
        double total = 0.0D;
        for (SurveyTarget.Candidate candidate : object.candidates()) {
            if (candidate.source() == OreIdentification.Source.ORE) {
                total += candidate.probability();
            }
        }
        return total;
    }

    public static Component candidateName(SurveyTarget.Candidate candidate) {
        return SurveyText.candidate(
                candidate.source().ordinal(), categoryOf(candidate), candidate.oreId());
    }

    private static int categoryOf(SurveyTarget.Candidate candidate) {
        return candidate.category() == null ? -1 : candidate.category().ordinal();
    }

    /** Opaque RGB of an object: its likely material, or the void's filling. */
    public static int color(LidarObject object) {
        return switch (object.kind()) {
            case CAVE, CORE_VOID -> LidarExtraction.caveColor((byte) object.fill());
            case TARGET, CORE_ORE -> {
                if (object.candidates().isEmpty()) {
                    yield 0x9A9A9A;
                }
                SurveyTarget.Candidate best = object.candidates().getFirst();
                if (object.kind() == LidarObject.Kind.TARGET
                        && best.probability() < NAMING_PROBABILITY
                        && oreProbability(object) < NAMING_PROBABILITY) {
                    yield 0xB8B8C8;
                }
                yield SurveyText.color(best.source().ordinal(), categoryOf(best), best.oreId());
            }
        };
    }

    private static Component text(String key, Object... args) {
        return SurveyText.text("lidar." + key, args);
    }
}
