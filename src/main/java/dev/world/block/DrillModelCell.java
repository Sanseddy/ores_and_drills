package dev.world.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact, non-axis-aligned model geometry clipped to one block-local 16x16x16 cell. */
public final class DrillModelCell {
    private static final double EPSILON = 1.0E-8D;
    static final DrillModelCell EMPTY = new DrillModelCell(List.of(), List.of());

    private final List<Triangle> triangles;
    private final List<Line> lines;

    private DrillModelCell(List<Triangle> triangles, List<Line> lines) {
        this.triangles = triangles;
        this.lines = lines;
    }

    public boolean isEmpty() {
        return triangles.isEmpty();
    }

    public List<Line> lines() {
        return lines;
    }

    public BlockHitResult clip(Vec3 worldStart, Vec3 worldEnd, BlockPos blockPos) {
        Vec3 origin = Vec3.atLowerCornerOf(blockPos);
        Vec3 start = worldStart.subtract(origin);
        Vec3 delta = worldEnd.subtract(worldStart);
        Intersection nearest = null;

        for (Triangle triangle : triangles) {
            Intersection intersection = triangle.intersect(start, delta);
            if (intersection != null && (nearest == null || intersection.distance() < nearest.distance())) {
                nearest = intersection;
            }
        }
        if (nearest == null) {
            return null;
        }

        Vec3 normal = nearest.normal();
        if (normal.dot(delta) > 0.0D) {
            normal = normal.scale(-1.0D);
        }
        Vec3 location = worldStart.add(delta.scale(nearest.distance()));
        return new BlockHitResult(location, Direction.getNearest(normal.x, normal.y, normal.z), blockPos, false);
    }

    DrillModelCell rotateY(int clockwiseQuarterTurns) {
        int turns = Math.floorMod(clockwiseQuarterTurns, 4);
        if (turns == 0 || isEmpty()) {
            return this;
        }

        Builder builder = builder();
        for (Triangle triangle : triangles) {
            builder.addTriangle(
                    rotatePoint(triangle.first(), turns),
                    rotatePoint(triangle.second(), turns),
                    rotatePoint(triangle.third(), turns)
            );
        }
        for (Line line : lines) {
            builder.addLine(rotatePoint(line.start(), turns), rotatePoint(line.end(), turns));
        }
        return builder.build();
    }

    static Builder builder() {
        return new Builder();
    }

    private static Vec3 rotatePoint(Vec3 point, int turns) {
        return switch (turns) {
            case 1 -> new Vec3(1.0D - point.z, point.y, point.x);
            case 2 -> new Vec3(1.0D - point.x, point.y, 1.0D - point.z);
            case 3 -> new Vec3(point.z, point.y, 1.0D - point.x);
            default -> point;
        };
    }

    public record Line(Vec3 start, Vec3 end) {
    }

    private record Triangle(Vec3 first, Vec3 second, Vec3 third, Vec3 edgeOne, Vec3 edgeTwo, Vec3 normal) {
        private Triangle(Vec3 first, Vec3 second, Vec3 third) {
            this(
                    first,
                    second,
                    third,
                    second.subtract(first),
                    third.subtract(first),
                    second.subtract(first).cross(third.subtract(first)).normalize()
            );
        }

        private Intersection intersect(Vec3 start, Vec3 direction) {
            Vec3 perpendicular = direction.cross(edgeTwo);
            double determinant = edgeOne.dot(perpendicular);
            if (Math.abs(determinant) < EPSILON) {
                return null;
            }

            double inverse = 1.0D / determinant;
            Vec3 fromFirst = start.subtract(first);
            double secondWeight = fromFirst.dot(perpendicular) * inverse;
            if (secondWeight < -EPSILON || secondWeight > 1.0D + EPSILON) {
                return null;
            }

            Vec3 cross = fromFirst.cross(edgeOne);
            double thirdWeight = direction.dot(cross) * inverse;
            if (thirdWeight < -EPSILON || secondWeight + thirdWeight > 1.0D + EPSILON) {
                return null;
            }

            double distance = edgeTwo.dot(cross) * inverse;
            if (distance < -EPSILON || distance > 1.0D + EPSILON) {
                return null;
            }
            return new Intersection(Mth.clamp(distance, 0.0D, 1.0D), normal);
        }
    }

    private record Intersection(double distance, Vec3 normal) {
    }

    static final class Builder {
        private static final double KEY_SCALE = 1.0E7D;
        private final List<Triangle> triangles = new ArrayList<>();
        private final List<Line> lines = new ArrayList<>();
        private final Set<LineKey> lineKeys = new HashSet<>();

        void addPolygon(List<Vec3> polygon) {
            if (polygon.size() < 3) {
                return;
            }
            Vec3 first = polygon.getFirst();
            for (int index = 1; index < polygon.size() - 1; index++) {
                addTriangle(first, polygon.get(index), polygon.get(index + 1));
            }
            for (int index = 0; index < polygon.size(); index++) {
                addLine(polygon.get(index), polygon.get((index + 1) % polygon.size()));
            }
        }

        private void addTriangle(Vec3 first, Vec3 second, Vec3 third) {
            if (second.subtract(first).cross(third.subtract(first)).lengthSqr() <= EPSILON * EPSILON) {
                return;
            }
            triangles.add(new Triangle(first, second, third));
        }

        private void addLine(Vec3 start, Vec3 end) {
            if (start.distanceToSqr(end) <= EPSILON * EPSILON) {
                return;
            }
            LineKey key = LineKey.of(start, end);
            if (lineKeys.add(key)) {
                lines.add(new Line(start, end));
            }
        }

        DrillModelCell build() {
            return triangles.isEmpty() ? EMPTY : new DrillModelCell(List.copyOf(triangles), List.copyOf(lines));
        }

        private record PointKey(long x, long y, long z) implements Comparable<PointKey> {
            private static PointKey of(Vec3 point) {
                return new PointKey(
                        Math.round(point.x * KEY_SCALE),
                        Math.round(point.y * KEY_SCALE),
                        Math.round(point.z * KEY_SCALE)
                );
            }

            @Override
            public int compareTo(PointKey other) {
                int xComparison = Long.compare(x, other.x);
                if (xComparison != 0) {
                    return xComparison;
                }
                int yComparison = Long.compare(y, other.y);
                return yComparison != 0 ? yComparison : Long.compare(z, other.z);
            }
        }

        private record LineKey(PointKey first, PointKey second) {
            private LineKey {
                if (first.compareTo(second) > 0) {
                    PointKey swap = first;
                    first = second;
                    second = swap;
                }
            }

            private static LineKey of(Vec3 start, Vec3 end) {
                return new LineKey(PointKey.of(start), PointKey.of(end));
            }
        }
    }
}
