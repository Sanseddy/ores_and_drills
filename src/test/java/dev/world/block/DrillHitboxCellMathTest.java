package dev.world.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class DrillHitboxCellMathTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void flatAxesReceiveOnePixelOfCentredCollisionThickness() {
        DrillHitboxCellMath.Box flat = new DrillHitboxCellMath.Box(
                -0.25D, 0.5D, 0.75D,
                0.75D, 0.5D, 0.75D
        );

        DrillHitboxCellMath.Box expanded = DrillHitboxCellMath.expandFlatAxes(flat, 1.0D / 16.0D);

        assertBox(expanded, -0.25D, 0.46875D, 0.71875D, 0.75D, 0.53125D, 0.78125D);
    }

    @Test
    void volumetricAxesAreNotChanged() {
        DrillHitboxCellMath.Box volume = new DrillHitboxCellMath.Box(
                0.1D, 0.2D, 0.3D,
                0.4D, 0.5D, 0.6D
        );

        assertEquals(volume, DrillHitboxCellMath.expandFlatAxes(volume, 1.0D / 16.0D));
    }

    @Test
    void collisionThicknessMustBePositive() {
        DrillHitboxCellMath.Box flat = new DrillHitboxCellMath.Box(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 0.0D
        );

        assertThrows(IllegalArgumentException.class, () -> DrillHitboxCellMath.expandFlatAxes(flat, 0.0D));
    }

    @Test
    void crossingGeometryIsClippedAndTranslatedIntoTheSelectedCell() {
        DrillHitboxCellMath.Box model = new DrillHitboxCellMath.Box(
                -0.25D, 0.2D, -0.4D,
                1.25D, 1.4D, 0.6D
        );

        DrillHitboxCellMath.Box centre = DrillHitboxCellMath.clipToCell(model, 0.0D, 0.0D, 0.0D);
        DrillHitboxCellMath.Box left = DrillHitboxCellMath.clipToCell(model, -1.0D, 0.0D, 0.0D);
        DrillHitboxCellMath.Box above = DrillHitboxCellMath.clipToCell(model, 0.0D, 1.0D, 0.0D);

        assertBox(centre, 0.0D, 0.2D, 0.0D, 1.0D, 1.0D, 0.6D);
        assertBox(left, 0.75D, 0.2D, 0.0D, 1.0D, 1.0D, 0.6D);
        assertBox(above, 0.0D, 0.0D, 0.0D, 1.0D, 0.4D, 0.6D);
    }

    @Test
    void geometryOutsideTheSelectedCellIsAbsent() {
        DrillHitboxCellMath.Box model = new DrillHitboxCellMath.Box(
                0.1D, 0.1D, 0.1D,
                0.9D, 0.9D, 0.9D
        );

        assertNull(DrillHitboxCellMath.clipToCell(model, 1.0D, 0.0D, 0.0D));
        assertNull(DrillHitboxCellMath.clipToCell(model, 0.0D, -1.0D, 0.0D));
        assertNull(DrillHitboxCellMath.clipToCell(model, 0.0D, 0.0D, 1.0D));
    }

    @Test
    void clippedCellBoxRotatesAroundItsOwnSixteenPixelCentre() {
        DrillHitboxCellMath.Box north = new DrillHitboxCellMath.Box(
                0.1D, 0.2D, 0.3D,
                0.4D, 0.5D, 0.6D
        );

        assertBox(DrillHitboxCellMath.rotateY(north, 1), 0.4D, 0.2D, 0.1D, 0.7D, 0.5D, 0.4D);
        assertBox(DrillHitboxCellMath.rotateY(north, 2), 0.6D, 0.2D, 0.4D, 0.9D, 0.5D, 0.7D);
        assertBox(DrillHitboxCellMath.rotateY(north, 3), 0.3D, 0.2D, 0.6D, 0.6D, 0.5D, 0.9D);
        assertBox(DrillHitboxCellMath.rotateY(north, 4), 0.1D, 0.2D, 0.3D, 0.4D, 0.5D, 0.6D);
    }

    private static void assertBox(
            DrillHitboxCellMath.Box actual,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ
    ) {
        assertEquals(minX, actual.minX(), EPSILON);
        assertEquals(minY, actual.minY(), EPSILON);
        assertEquals(minZ, actual.minZ(), EPSILON);
        assertEquals(maxX, actual.maxX(), EPSILON);
        assertEquals(maxY, actual.maxY(), EPSILON);
        assertEquals(maxZ, actual.maxZ(), EPSILON);
    }
}
