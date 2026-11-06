package dev.world.geology.exploration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * The core log carried by a core sample: the rock column the borehole passed through, where it hit
 * ore and what the logging tool sensed beside the hole.
 *
 * @param intervals merged runs of one material, top to bottom
 * @param ore the ore intersected first, if any
 * @param localUnits ore units within {@link ExplorationDrillBlockEntity#ESTIMATE_RADIUS} blocks of
 *     the intersection; an estimate of the probed part only
 * @param industrial whether the intersected body is a MEDIUM/LARGE deposit
 * @param signals heights where ore sat within logging range of the hole but not in it
 * @param level upgrade level of the drill's analyser
 * @param analysis what the analyser identified the intersected ore as, best first; empty for a dry
 *     hole
 */
public record BoreholeLog(
        BlockPos top,
        int bottomY,
        List<Interval> intervals,
        Optional<ResourceLocation> ore,
        int oreTopY,
        int oreBottomY,
        int localUnits,
        boolean industrial,
        List<Integer> signals,
        int level,
        List<Analysis> analysis) {
    public static final int MAXIMUM_INTERVALS = 96;
    public static final int MAXIMUM_SIGNALS = 64;
    public static final int MAXIMUM_ANALYSIS = 8;

    /**
     * One identification candidate of the core.
     *
     * @param oreId ore block id, or empty when the analyser only names the class
     * @param category {@code OreTraits.Category} ordinal
     */
    public record Analysis(String oreId, int category, float probability) {
        public static final Codec<Analysis> CODEC =
                RecordCodecBuilder.create(
                        instance ->
                                instance.group(
                                                Codec.STRING
                                                        .optionalFieldOf("ore", "")
                                                        .forGetter(Analysis::oreId),
                                                Codec.INT
                                                        .fieldOf("category")
                                                        .forGetter(Analysis::category),
                                                Codec.FLOAT
                                                        .fieldOf("p")
                                                        .forGetter(Analysis::probability))
                                        .apply(instance, Analysis::new));
    }

    public enum Kind {
        ROCK,
        OPEN,
        FLUID,
        ORE;

        public static Kind byId(int id) {
            Kind[] kinds = values();
            return id >= 0 && id < kinds.length ? kinds[id] : ROCK;
        }
    }

    /** One run of the same block, heights inclusive. */
    public record Interval(int topY, int bottomY, ResourceLocation block, Kind kind) {
        public static final Codec<Interval> CODEC =
                RecordCodecBuilder.create(
                        instance ->
                                instance.group(
                                                Codec.INT.fieldOf("top").forGetter(Interval::topY),
                                                Codec.INT
                                                        .fieldOf("bottom")
                                                        .forGetter(Interval::bottomY),
                                                ResourceLocation.CODEC
                                                        .fieldOf("block")
                                                        .forGetter(Interval::block),
                                                Codec.INT
                                                        .xmap(Kind::byId, Kind::ordinal)
                                                        .fieldOf("kind")
                                                        .forGetter(Interval::kind))
                                        .apply(instance, Interval::new));
    }

    public BoreholeLog {
        top = top.immutable();
        intervals = List.copyOf(intervals);
        signals = List.copyOf(signals);
        analysis = List.copyOf(analysis);
    }

    public static final Codec<BoreholeLog> CODEC =
            RecordCodecBuilder.create(
                    instance ->
                            instance.group(
                                            BlockPos.CODEC
                                                    .fieldOf("top")
                                                    .forGetter(BoreholeLog::top),
                                            Codec.INT
                                                    .fieldOf("bottom")
                                                    .forGetter(BoreholeLog::bottomY),
                                            Interval.CODEC
                                                    .sizeLimitedListOf(MAXIMUM_INTERVALS)
                                                    .fieldOf("intervals")
                                                    .forGetter(BoreholeLog::intervals),
                                            ResourceLocation.CODEC
                                                    .optionalFieldOf("ore")
                                                    .forGetter(BoreholeLog::ore),
                                            Codec.INT
                                                    .optionalFieldOf("ore_top", 0)
                                                    .forGetter(BoreholeLog::oreTopY),
                                            Codec.INT
                                                    .optionalFieldOf("ore_bottom", 0)
                                                    .forGetter(BoreholeLog::oreBottomY),
                                            Codec.INT
                                                    .optionalFieldOf("units", 0)
                                                    .forGetter(BoreholeLog::localUnits),
                                            Codec.BOOL
                                                    .optionalFieldOf("industrial", false)
                                                    .forGetter(BoreholeLog::industrial),
                                            Codec.INT
                                                    .sizeLimitedListOf(MAXIMUM_SIGNALS)
                                                    .optionalFieldOf("signals", List.of())
                                                    .forGetter(BoreholeLog::signals),
                                            Codec.INT
                                                    .optionalFieldOf("level", 0)
                                                    .forGetter(BoreholeLog::level),
                                            Analysis.CODEC
                                                    .sizeLimitedListOf(MAXIMUM_ANALYSIS)
                                                    .optionalFieldOf("analysis", List.of())
                                                    .forGetter(BoreholeLog::analysis))
                                    .apply(instance, BoreholeLog::new));

    public static final StreamCodec<ByteBuf, BoreholeLog> STREAM_CODEC =
            ByteBufCodecs.fromCodec(CODEC);
}
