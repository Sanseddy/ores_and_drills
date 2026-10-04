package dev.client.ore.speck;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreSpeckAnalysisTest {
    @Test
    void countsSpecksOfEachTypeOnTheOriginalTexture() {
        OreSpeckAnalysis analysis = SpeckTestTextures.exampleAnalysis();

        assertEquals(11, analysis.total());
        assertEquals(5, analysis.count(OreSpeckType.TYPE_1));
        assertEquals(3, analysis.count(OreSpeckType.TYPE_2));
        assertEquals(2, analysis.count(OreSpeckType.TYPE_3));
        assertEquals(1, analysis.count(OreSpeckType.TYPE_4));
        assertEquals(Map.of(1, 4, 2, 1, 3, 2, 4, 1, 5, 1, 8, 1, 10, 1), analysis.sizeHistogram());
        assertEquals(OreSpeckAnalysis.Source.HOST_AND_PALETTE, analysis.source());
    }

    @Test
    void keepsShapeCoordinatesAndOriginalColorsOfEachSpeck() {
        OreSpeckAnalysis analysis = SpeckTestTextures.exampleAnalysis();
        OreSpeck largest = analysis.specksOf(OreSpeckType.TYPE_4).get(0);

        assertEquals(4, largest.originX());
        assertEquals(8, largest.originY());
        assertEquals(4, largest.width());
        assertEquals(3, largest.height());
        assertArrayEquals(new boolean[] {
                true, true, true, true,
                true, true, true, true,
                false, true, true, false
        }, largest.shapeMask());
        for (int pixel = 0; pixel < largest.size(); pixel++) {
            int x = largest.originX() + largest.pixelX(pixel);
            int y = largest.originY() + largest.pixelY(pixel);
            int expected = 0xFF000000 | SpeckTestTextures.IRON_PALETTE[(x + y) % SpeckTestTextures.IRON_PALETTE.length];
            assertEquals(expected, largest.pixelColor(pixel));
        }
    }

    @Test
    void diagonallyTouchingPixelsFormOneSpeck() {
        ArgbImage host = SpeckTestTextures.host();
        String[] map = emptyMap();
        map[3] = "...x............";
        map[4] = "....x...........";
        map[5] = ".....x..........";
        OreSpeckAnalysis analysis = OreSpeckAnalysis.analyze(
                SpeckTestTextures.ore(host, map, SpeckTestTextures.IRON_PALETTE), host, SpeckTestTextures.IRON_PALETTE
        );

        assertEquals(1, analysis.total());
        assertEquals(OreSpeckType.TYPE_2, analysis.specks().get(0).type());
    }

    @Test
    void greyOreIsNotConfusedWithGreyHostRock() {
        int[] silver = {0x9A9A9A, 0xC8C8C8, 0x5E5E5E};
        ArgbImage host = SpeckTestTextures.host();
        OreSpeckAnalysis analysis = OreSpeckAnalysis.analyze(
                SpeckTestTextures.ore(host, SpeckTestTextures.EXAMPLE_MAP, silver), host, silver
        );

        assertEquals(11, analysis.total());
    }

    @Test
    void detectsDistinctlyColoredOreWithoutKnownHost() {
        int[] diamond = {0x4AEDD9, 0xA1FBE8, 0x1AAAA7};
        ArgbImage ore = SpeckTestTextures.ore(SpeckTestTextures.host(), SpeckTestTextures.EXAMPLE_MAP, diamond);
        OreSpeckAnalysis analysis = OreSpeckAnalysis.analyze(ore, null, diamond);

        assertEquals(11, analysis.total());
        assertEquals(OreSpeckAnalysis.Source.PALETTE, analysis.source());
    }

    @Test
    void picksTheHostTextureTheOreWasPaintedOn() {
        ArgbImage stone = SpeckTestTextures.host();
        int[] otherPixels = new int[256];
        java.util.Arrays.fill(otherPixels, 0xFF3A2020);
        ArgbImage netherrack = new ArgbImage(16, 16, otherPixels);
        ArgbImage ore = SpeckTestTextures.ore(stone, SpeckTestTextures.EXAMPLE_MAP, SpeckTestTextures.IRON_PALETTE);

        assertSame(stone, OreSpeckAnalysis.pickHost(ore, List.of(netherrack, stone)));
        assertNull(OreSpeckAnalysis.pickHost(ore, List.of(netherrack)));
    }

    @Test
    void higherResolutionTexturesAreReadOnTheSixteenPixelGrid() {
        ArgbImage host = SpeckTestTextures.host();
        ArgbImage ore = SpeckTestTextures.ore(host, SpeckTestTextures.EXAMPLE_MAP, SpeckTestTextures.IRON_PALETTE);
        OreSpeckAnalysis analysis = OreSpeckAnalysis.analyze(upscale(ore), upscale(host), SpeckTestTextures.IRON_PALETTE);

        assertEquals(11, analysis.total());
    }

    @Test
    void syntheticTemplatesCoverEveryTypeExceptTheLargest() {
        OreSpeckAnalysis analysis = OreSpeckAnalysis.synthetic(SpeckTestTextures.IRON_PALETTE);

        assertTrue(analysis.count(OreSpeckType.TYPE_1) > 0);
        assertTrue(analysis.count(OreSpeckType.TYPE_2) > 0);
        assertTrue(analysis.count(OreSpeckType.TYPE_3) > 0);
        assertEquals(0, OreSpeckAnalysis.synthetic(new int[0]).total());
    }

    @Test
    void classifiesSizesIntoFourTypes() {
        assertEquals(OreSpeckType.TYPE_1, OreSpeckType.forSize(1));
        assertEquals(OreSpeckType.TYPE_1, OreSpeckType.forSize(2));
        assertEquals(OreSpeckType.TYPE_2, OreSpeckType.forSize(3));
        assertEquals(OreSpeckType.TYPE_2, OreSpeckType.forSize(4));
        assertEquals(OreSpeckType.TYPE_3, OreSpeckType.forSize(5));
        assertEquals(OreSpeckType.TYPE_3, OreSpeckType.forSize(8));
        assertEquals(OreSpeckType.TYPE_4, OreSpeckType.forSize(9));
        assertEquals(OreSpeckType.TYPE_4, OreSpeckType.forSize(40));
    }

    private static String[] emptyMap() {
        String[] map = new String[16];
        java.util.Arrays.fill(map, "................");
        return map;
    }

    private static ArgbImage upscale(ArgbImage image) {
        int[] pixels = new int[32 * 32];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                pixels[y * 32 + x] = image.get(x / 2, y / 2);
            }
        }
        return new ArgbImage(32, 32, pixels);
    }
}
