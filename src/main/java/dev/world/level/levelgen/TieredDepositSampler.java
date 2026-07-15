package dev.world.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Volume sampler whose probe distribution and cost are selected before any terrain lookup. */
public final class TieredDepositSampler implements DepositSampler {
    private static final long SAMPLE_SALT = 0x53414D504C45524CL;

    @Override
    public List<SamplePoint> createSamples(DepositCandidate candidate, DepositTierLayout layout) {
        int sampleCount = Mth.clamp(
                layout.minimumSamples() + (int) Math.ceil(Math.cbrt(candidate.expectedBlocks()) * 2.0D),
                layout.minimumSamples(),
                layout.maximumSamples()
        );
        Map<BlockPos, Integer> points = new LinkedHashMap<>(sampleCount * 2);
        BlockPos center = candidate.center();
        add(points, candidate, center);

        double[] shells = switch (candidate.tier()) {
            case TINY -> new double[]{0.45D, 0.80D};
            case SMALL -> new double[]{0.25D, 0.55D, 0.85D};
            case MEDIUM -> new double[]{0.20D, 0.45D, 0.70D, 0.90D};
            case LARGE -> new double[]{0.15D, 0.35D, 0.55D, 0.75D, 0.92D};
        };
        for (double shell : shells) {
            int dx = Math.max(1, (int) Math.floor(candidate.radiusX() * shell));
            int dz = Math.max(1, (int) Math.floor(candidate.radiusZ() * shell));
            int dy = Math.max(1, (int) Math.floor(candidate.verticalRadius() * shell));
            add(points, candidate, center.offset(dx, 0, 0));
            add(points, candidate, center.offset(-dx, 0, 0));
            add(points, candidate, center.offset(0, 0, dz));
            add(points, candidate, center.offset(0, 0, -dz));
            add(points, candidate, center.offset(dx / 2, -dy / 2, dz / 2));
            add(points, candidate, center.offset(-dx / 2, -dy / 2, -dz / 2));
            add(points, candidate, center.offset(0, -dy, 0));
        }

        RandomSource random = RandomSource.create(
                candidate.shapeSeed() ^ candidate.depositId() ^ DepositLayouts.tierSalt(candidate.tier()) ^ SAMPLE_SALT
        );
        int attempts = 0;
        int maximumAttempts = sampleCount * 48;
        while (points.size() < sampleCount && attempts++ < maximumAttempts) {
            double x = random.nextDouble() * 2.0D - 1.0D;
            double y = -random.nextDouble();
            double z = random.nextDouble() * 2.0D - 1.0D;
            if (x * x + y * y + z * z > 1.0D) {
                continue;
            }
            int dx = (int) Math.round(x * candidate.radiusX());
            int dy = (int) Math.round(y * candidate.verticalRadius());
            int dz = (int) Math.round(z * candidate.radiusZ());
            if (square((double) dx / candidate.radiusX())
                    + square((double) dy / candidate.verticalRadius())
                    + square((double) dz / candidate.radiusZ()) > 1.0D) {
                continue;
            }
            add(points, candidate, center.offset(dx, dy, dz));
        }

        List<SamplePoint> result = new ArrayList<>(points.size());
        points.forEach((pos, section) -> result.add(new SamplePoint(pos, section)));
        return List.copyOf(result);
    }

    public static int minimumPlacedBlocks(DepositCandidate candidate) {
        double minimumRatio = switch (candidate.tier()) {
            case TINY -> 0.30D;
            case SMALL -> 0.34D;
            case MEDIUM -> 0.38D;
            case LARGE -> 0.42D;
        };
        return Math.max(1, (int) Math.ceil(candidate.expectedBlocks() * minimumRatio));
    }

    public static int minimumOccupiedSections(OreDepositTier tier) {
        return switch (tier) {
            case TINY, SMALL -> 1;
            case MEDIUM -> 2;
            case LARGE -> 3;
        };
    }

    public static int minimumValidSections(OreDepositTier tier) {
        return switch (tier) {
            case TINY -> 2;
            case SMALL -> 4;
            case MEDIUM -> 7;
            case LARGE -> 10;
        };
    }

    private static void add(Map<BlockPos, Integer> points, DepositCandidate candidate, BlockPos pos) {
        points.putIfAbsent(pos.immutable(), section(candidate, pos));
    }

    private static int section(DepositCandidate candidate, BlockPos pos) {
        double x = (double) (pos.getX() - candidate.center().getX()) / candidate.radiusX();
        double z = (double) (pos.getZ() - candidate.center().getZ()) / candidate.radiusZ();
        double radius = Math.sqrt(x * x + z * z);
        int shell = radius < 0.25D ? 0 : radius < 0.55D ? 1 : radius < 0.80D ? 2 : 3;
        int side;
        if (Math.abs(x) >= Math.abs(z)) {
            side = x >= 0.0D ? 0 : 2;
        } else {
            side = z >= 0.0D ? 1 : 3;
        }
        double depth = (double) (candidate.center().getY() - pos.getY()) / candidate.verticalRadius();
        int vertical = depth < 0.34D ? 0 : depth < 0.68D ? 1 : 2;
        return vertical * 16 + shell * 4 + side;
    }

    private static double square(double value) {
        return value * value;
    }
}
