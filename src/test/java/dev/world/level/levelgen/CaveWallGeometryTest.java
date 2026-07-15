package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaveWallGeometryTest {
    private static final WallDepositGeometry.Point ANCHOR = point(0, 0, 0);

    @Test
    void smallShapeIsConnectedAndKeepsMostBlocksInsideTheWall() {
        assertTrue(WallDepositGeometry.isValid(
                List.of(ANCHOR, point(0, 0, 1), point(0, 0, 2), point(1, 0, 1)),
                ANCHOR,
                0,
                1,
                true
        ));
        assertFalse(WallDepositGeometry.isValid(
                List.of(ANCHOR, point(1, 0, 0), point(-1, 0, 0)),
                ANCHOR,
                0,
                1,
                true
        ));
    }

    @Test
    void shapeCannotJumpThroughAGapOrGrowOutOfTheWall() {
        assertFalse(WallDepositGeometry.isValid(
                List.of(ANCHOR, point(0, 0, 2)),
                ANCHOR,
                0,
                1,
                false
        ));
        assertFalse(WallDepositGeometry.isValid(
                List.of(ANCHOR, point(0, 0, -1)),
                ANCHOR,
                0,
                1,
                false
        ));
    }

    private static WallDepositGeometry.Point point(int x, int y, int z) {
        return new WallDepositGeometry.Point(x, y, z);
    }
}
