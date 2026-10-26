package dev.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;

/** Extensions register a codec once during mod construction, on both physical sides. */
public interface DrillMiningCondition {
    Map<ResourceLocation, MapCodec<? extends DrillMiningCondition>> TYPES =
            new ConcurrentHashMap<>();
    Codec<DrillMiningCondition> CODEC =
            ResourceLocation.CODEC
                    .validate(
                            id ->
                                    TYPES.containsKey(id)
                                            ? DataResult.success(id)
                                            : DataResult.error(
                                                    () -> "Unknown drill mining condition " + id))
                    .dispatch("type", DrillMiningCondition::type, TYPES::get);

    ResourceLocation type();

    boolean test(ServerLevel level, DrillMiningInput input);

    static void register(ResourceLocation id, MapCodec<? extends DrillMiningCondition> codec) {
        if (TYPES.putIfAbsent(id, codec) != null)
            throw new IllegalStateException("Duplicate mining condition " + id);
    }

    static void registerDefaults() {
        register(Dimension.ID, Dimension.CODEC);
        register(Height.ID, Height.CODEC);
        register(Biome.ID, Biome.CODEC);
        register(Redstone.ID, Redstone.CODEC);
    }

    record Dimension(ResourceLocation dimension) implements DrillMiningCondition {
        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath("ores_and_drills", "dimension");
        public static final MapCodec<Dimension> CODEC =
                ResourceLocation.CODEC
                        .fieldOf("dimension")
                        .xmap(Dimension::new, Dimension::dimension);

        public ResourceLocation type() {
            return ID;
        }

        public boolean test(ServerLevel level, DrillMiningInput input) {
            return level.dimension().location().equals(dimension);
        }
    }

    record Height(int min, int max) implements DrillMiningCondition {
        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath("ores_and_drills", "height");
        public static final MapCodec<Height> CODEC =
                RecordCodecBuilder.<Height>mapCodec(
                                instance ->
                                        instance.group(
                                                        Codec.INT
                                                                .optionalFieldOf(
                                                                        "min", Integer.MIN_VALUE)
                                                                .forGetter(Height::min),
                                                        Codec.INT
                                                                .optionalFieldOf(
                                                                        "max", Integer.MAX_VALUE)
                                                                .forGetter(Height::max))
                                                .apply(instance, Height::new))
                        .validate(
                                value ->
                                        value.min <= value.max
                                                ? DataResult.success(value)
                                                : DataResult.error(
                                                        () ->
                                                                "Mining height min must not exceed"
                                                                        + " max"));

        public ResourceLocation type() {
            return ID;
        }

        public boolean test(ServerLevel level, DrillMiningInput input) {
            return input.orePos().getY() >= min && input.orePos().getY() <= max;
        }
    }

    record Biome(ResourceLocation tag) implements DrillMiningCondition {
        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath("ores_and_drills", "biome");
        public static final MapCodec<Biome> CODEC =
                ResourceLocation.CODEC.fieldOf("tag").xmap(Biome::new, Biome::tag);

        public ResourceLocation type() {
            return ID;
        }

        public boolean test(ServerLevel level, DrillMiningInput input) {
            return level.getBiome(input.orePos()).is(TagKey.create(Registries.BIOME, tag));
        }
    }

    record Redstone(boolean powered) implements DrillMiningCondition {
        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath("ores_and_drills", "redstone");
        public static final MapCodec<Redstone> CODEC =
                Codec.BOOL.optionalFieldOf("powered", true).xmap(Redstone::new, Redstone::powered);

        public ResourceLocation type() {
            return ID;
        }

        public boolean test(ServerLevel level, DrillMiningInput input) {
            return level.hasNeighborSignal(input.drillPos()) == powered;
        }
    }
}
