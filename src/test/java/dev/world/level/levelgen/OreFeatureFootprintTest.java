package dev.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OreFeatureFootprintTest {
    @Test
    void reachFollowsTheVanillaBoundingSquarePlusTheAirCheck() {
        // ceil(size / 8) + ceil((size / 8 + 1) / 2) + 1
        assertEquals(2, OreFeatureFootprint.horizontalReach(0));
        assertEquals(3, OreFeatureFootprint.horizontalReach(8));
        assertEquals(5, OreFeatureFootprint.horizontalReach(9));
        assertEquals(6, OreFeatureFootprint.horizontalReach(17));
        assertEquals(14, OreFeatureFootprint.horizontalReach(64));
    }

    @Test
    void everyVeinStartedInTheDecoratedChunkFitsTheGeneratedArea() {
        // Attempts from the source's own placement start in the decorated chunk: none may be
        // refused, so frequency and size stay exactly what they were.
        GenerationWriteBounds area =
                GenerationWriteBounds.around(-3, 1, OreDepositFeature.FEATURE_WRITE_RADIUS_CHUNKS);
        for (int size = 0; size <= 64; size++) {
            int reach = OreFeatureFootprint.horizontalReach(size);
            for (int x = -48; x <= -33; x++) {
                for (int z = 16; z <= 31; z++) {
                    assertTrue(
                            area.containsSquare(x, z, reach),
                            "size " + size + " at " + x + ", " + z);
                }
            }
        }
    }

    /**
     * The reported crash: decorating chunk (-3, 1), a lead vein of size 8 searched for a cave wall.
     * The chunk east of the readable 3x3 had no terrain yet and read as air, so the last stone
     * column of the area looked like a wall and the vein started in chunk (-1, 1), where
     * Cataclysm's hook could not get structure data.
     */
    @Test
    void wallOnTheEdgeOfTheGeneratedAreaCannotStartAVeinOutsideIt() {
        GenerationWriteBounds area =
                GenerationWriteBounds.around(-3, 1, OreDepositFeature.FEATURE_WRITE_RADIUS_CHUNKS);
        int size = 8;
        int wallX = (-2 << 4) + 15;
        int frontX = wallX + 1;
        int z = 20;

        assertTrue(area.contains(wallX, z));
        assertFalse(
                area.contains(frontX, z),
                "the air block in front of the wall is in an unavailable chunk");
        assertEquals(-1, frontX >> 4);
        assertFalse(area.containsSquare(frontX, z, OreFeatureFootprint.horizontalReach(size)));

        GenerationWriteBounds wallArea = area.inset(OreFeatureFootprint.horizontalReach(size) + 1);
        assertFalse(wallArea.contains(wallX, z));
        int innermostWall = wallArea.maximumX();
        assertTrue(
                area.containsSquare(
                        innermostWall + 1, z, OreFeatureFootprint.horizontalReach(size)));
        assertTrue(
                area.containsSquare(
                        innermostWall - 1, z, OreFeatureFootprint.horizontalReach(size)));
    }
}
