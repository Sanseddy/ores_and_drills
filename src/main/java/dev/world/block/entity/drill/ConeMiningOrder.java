package dev.world.block.entity.drill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure, game-independent order in which a drill excavates its mining area: a funnel that starts as the single
 * block at the top centre, right under the bit, and widens step by step.
 *
 * <p>A block's rank is {@code r + d * R / D}: its horizontal distance {@code r} from the centre column(s) plus its
 * depth {@code d} scaled so that a funnel of rank {@code R} (half the body's width) is as wide as the body at the
 * surface and narrows to a point at the bottom of the scanned depth {@code D}. The funnel grows in whole stages:
 * stage {@code s} holds the blocks of rank up to {@code s}, so stage 0 is the top centre block alone and each later
 * stage adds a wider and deeper shell around the previous one. Within a stage blocks are ordered by rank, then
 * depth, then along the column spiral.
 */
final class ConeMiningOrder {
    /** @param stage the funnel step that first reaches this block; 0 is the top centre */
    record Cell(int x, int z, int depth, int stage) {
    }

    private record Key(int bodySize, int margin, int depth) {
    }

    private static final double EPSILON = 1.0E-9D;
    private static final Map<Key, List<Cell>> CACHE = new ConcurrentHashMap<>();

    private ConeMiningOrder() {
    }

    /**
     * Cells of the mining area of a {@code bodySize} drill that reaches {@code margin} columns past its body, down
     * to {@code depth} layers below it; column offsets are in the drill structure's x/z frame.
     */
    static List<Cell> of(int bodySize, int margin, int depth) {
        return CACHE.computeIfAbsent(new Key(bodySize, margin, depth), ConeMiningOrder::compute);
    }

    private static List<Cell> compute(Key key) {
        List<SpiralColumnOrder.Offset> columns = SpiralColumnOrder.between(-key.margin(), key.bodySize() + key.margin());
        List<Ranked> ranked = new ArrayList<>(columns.size() * key.depth());
        for (int column = 0; column < columns.size(); column++) {
            SpiralColumnOrder.Offset offset = columns.get(column);
            for (int layer = 0; layer < key.depth(); layer++) {
                double rank = rank(offset.x(), offset.z(), layer, key.bodySize(), key.depth());
                int stage = (int) Math.ceil(rank - EPSILON);
                ranked.add(new Ranked(new Cell(offset.x(), offset.z(), layer, stage), rank, column));
            }
        }
        ranked.sort(Comparator.comparingInt((Ranked entry) -> entry.cell().stage())
                .thenComparingDouble(Ranked::rank)
                .thenComparingInt(entry -> entry.cell().depth())
                .thenComparingInt(Ranked::spiralIndex));

        List<Cell> cells = new ArrayList<>(ranked.size());
        for (Ranked entry : ranked) {
            cells.add(entry.cell());
        }
        return List.copyOf(cells);
    }

    /** The block's place in the funnel, in blocks; the cone right under the body has ranks up to half its width. */
    static double rank(int x, int z, int depth, int bodySize, int scanDepth) {
        double center = (bodySize - 1) / 2.0D;
        // An even body has four centre columns; they all start the funnel, so distances count from them.
        double centerOffset = bodySize % 2 == 0 ? Math.sqrt(0.5D) : 0.0D;
        double radius = Math.max(0.0D, Math.hypot(x - center, z - center) - centerOffset);
        double bodyRadius = Math.max(0.5D, bodySize / 2.0D);
        return radius + depth * bodyRadius / Math.max(1, scanDepth);
    }

    private record Ranked(Cell cell, double rank, int spiralIndex) {
    }
}
