package dev.world.level.levelgen;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure wall-shape invariants, kept independent of Minecraft classes for fast unit testing. */
final class WallDepositGeometry {
    private WallDepositGeometry() {
    }

    static boolean isValid(
            List<Point> positions,
            Point anchor,
            int inwardX,
            int inwardZ,
            boolean requireInsideMajority
    ) {
        if (anchor == null || positions.isEmpty() || !positions.contains(anchor)) {
            return false;
        }
        int insideBlocks = 0;
        for (Point pos : positions) {
            int inwardDepth = (pos.x() - anchor.x()) * inwardX
                    + (pos.z() - anchor.z()) * inwardZ;
            if (inwardDepth < 0) {
                return false;
            }
            if (inwardDepth > 0) {
                insideBlocks++;
            }
        }
        if (requireInsideMajority && insideBlocks * 2 < positions.size()) {
            return false;
        }

        Set<Point> connected = new HashSet<>();
        Set<Point> pending = new HashSet<>(positions);
        connected.add(anchor);
        pending.remove(anchor);
        boolean changed;
        do {
            changed = false;
            for (Point pos : List.copyOf(pending)) {
                if (hasNeighbor(pos, connected)) {
                    connected.add(pos);
                    pending.remove(pos);
                    changed = true;
                }
            }
        } while (changed && !pending.isEmpty());
        return pending.isEmpty();
    }

    private static boolean hasNeighbor(Point point, Set<Point> connected) {
        return connected.contains(new Point(point.x() + 1, point.y(), point.z()))
                || connected.contains(new Point(point.x() - 1, point.y(), point.z()))
                || connected.contains(new Point(point.x(), point.y() + 1, point.z()))
                || connected.contains(new Point(point.x(), point.y() - 1, point.z()))
                || connected.contains(new Point(point.x(), point.y(), point.z() + 1))
                || connected.contains(new Point(point.x(), point.y(), point.z() - 1));
    }

    record Point(int x, int y, int z) {
    }
}
