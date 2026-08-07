package dev.world.block;

/** Pure box clipping and quarter-turn math, independent from Minecraft runtime classes. */
final class DrillHitboxCellMath {
    private static final double EPSILON = 1.0E-5D;

    private DrillHitboxCellMath() {
    }

    static Box expandFlatAxes(Box box, double minimumThickness) {
        if (minimumThickness <= 0.0D) {
            throw new IllegalArgumentException("Minimum thickness must be positive");
        }

        double[] x = expandFlatAxis(box.minX(), box.maxX(), minimumThickness);
        double[] y = expandFlatAxis(box.minY(), box.maxY(), minimumThickness);
        double[] z = expandFlatAxis(box.minZ(), box.maxZ(), minimumThickness);
        return new Box(x[0], y[0], z[0], x[1], y[1], z[1]);
    }

    private static double[] expandFlatAxis(double minimum, double maximum, double thickness) {
        if (maximum - minimum > EPSILON) {
            return new double[]{minimum, maximum};
        }

        double centre = (minimum + maximum) * 0.5D;
        double halfThickness = thickness * 0.5D;
        return new double[]{centre - halfThickness, centre + halfThickness};
    }

    static Box clipToCell(Box modelBox, double cellX, double cellY, double cellZ) {
        double minX = Math.max(modelBox.minX(), cellX);
        double minY = Math.max(modelBox.minY(), cellY);
        double minZ = Math.max(modelBox.minZ(), cellZ);
        double maxX = Math.min(modelBox.maxX(), cellX + 1.0D);
        double maxY = Math.min(modelBox.maxY(), cellY + 1.0D);
        double maxZ = Math.min(modelBox.maxZ(), cellZ + 1.0D);
        if (maxX - minX <= EPSILON || maxY - minY <= EPSILON || maxZ - minZ <= EPSILON) {
            return null;
        }

        return new Box(
                clampUnit(minX - cellX),
                clampUnit(minY - cellY),
                clampUnit(minZ - cellZ),
                clampUnit(maxX - cellX),
                clampUnit(maxY - cellY),
                clampUnit(maxZ - cellZ)
        );
    }

    static Box rotateY(Box northBox, int clockwiseQuarterTurns) {
        return switch (Math.floorMod(clockwiseQuarterTurns, 4)) {
            case 0 -> northBox;
            case 1 -> new Box(
                    1.0D - northBox.maxZ(), northBox.minY(), northBox.minX(),
                    1.0D - northBox.minZ(), northBox.maxY(), northBox.maxX()
            );
            case 2 -> new Box(
                    1.0D - northBox.maxX(), northBox.minY(), 1.0D - northBox.maxZ(),
                    1.0D - northBox.minX(), northBox.maxY(), 1.0D - northBox.minZ()
            );
            case 3 -> new Box(
                    northBox.minZ(), northBox.minY(), 1.0D - northBox.maxX(),
                    northBox.maxZ(), northBox.maxY(), 1.0D - northBox.minX()
            );
            default -> throw new AssertionError("floorMod must return 0..3");
        };
    }

    private static double clampUnit(double value) {
        return Math.clamp(value, 0.0D, 1.0D);
    }

    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }
}
