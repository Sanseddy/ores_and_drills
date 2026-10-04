package dev.client.ore.speck;

import dev.world.level.levelgen.OreVisualStages;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreSpeckLayoutTest {
    private static final long SEED = 42L;

    @Test
    void normalStateReusesEveryOriginalSpeckOnceInsideTheInnerArea() {
        OreSpeckAnalysis analysis = SpeckTestTextures.exampleAnalysis();
        OreSpeckLayout layout = plan(analysis);
        List<OreSpeckLayout.Placement> normal = layout.placementsFor(1.0D);

        assertEquals(analysis.total(), layout.baseCount());
        assertEquals(analysis.total(), normal.size());
        for (OreSpeckType type : OreSpeckType.values()) {
            long placed = normal.stream().filter(placement -> placement.speck().type() == type).count();
            assertEquals(analysis.count(type), placed, "type " + type.number());
        }
        assertTrue(analysis.specks().containsAll(normal.stream().map(OreSpeckLayout.Placement::speck).toList()));
        for (OreSpeckLayout.Placement placement : normal) {
            assertFalse(placement.extra());
            assertTrue(placement.x() >= layout.innerMin() && placement.y() >= layout.innerMin());
            assertTrue(placement.x() + placement.speck().width() <= layout.innerMax());
            assertTrue(placement.y() + placement.speck().height() <= layout.innerMax());
        }
    }

    @Test
    void placesLargestTypesFirst() {
        List<OreSpeckLayout.Placement> normal = plan(SpeckTestTextures.exampleAnalysis()).normalPlacements();
        for (int index = 1; index < normal.size(); index++) {
            assertTrue(normal.get(index - 1).speck().type().ordinal() >= normal.get(index).speck().type().ordinal());
        }
    }

    @Test
    void normalSpecksStaySeparateSoTheirCountStaysVisible() {
        OreSpeckLayout layout = plan(SpeckTestTextures.exampleAnalysis());
        ArgbImage rendered = layout.renderOre(1.0D);
        boolean[] mask = new boolean[rendered.pixels().length];
        for (int index = 0; index < mask.length; index++) {
            mask[index] = ArgbImage.alpha(rendered.pixels()[index]) != 0;
        }

        assertEquals(layout.baseCount(), OreSpeckAnalysis.connectedSpecks(rendered, mask).size());
    }

    @Test
    void depletionRemovesWholeSpecksRelativeToTheOriginalCount() {
        OreSpeckAnalysis analysis = twelveSpecks();
        OreSpeckLayout layout = plan(analysis);

        assertFalse(layout.noiseMode());
        assertEquals(12, layout.placementsFor(1.0D).size());
        assertEquals(9, layout.placementsFor(0.75D).size());
        assertEquals(6, layout.placementsFor(0.5D).size());
        assertEquals(3, layout.placementsFor(0.25D).size());
        assertEquals(1, layout.placementsFor(0.08D).size());
        assertEquals(0, layout.placementsFor(0.0D).size());
    }

    @Test
    void typeOneSpecksAreOnlyAnAdditionOnTopOfVisibleOnes() {
        OreSpeckLayout layout = plan(twelveSpecks());
        for (double fill : new double[] {0.08D, 0.2D, 0.25D, 0.5D}) {
            List<OreSpeckLayout.Placement> kept = layout.placementsFor(fill);
            assertTrue(kept.stream().noneMatch(placement -> placement.speck().type() == OreSpeckType.TYPE_1), "fill " + fill);
        }
        assertTrue(layout.placementsFor(0.75D).stream().anyMatch(placement -> placement.speck().type() == OreSpeckType.TYPE_1));
    }

    @Test
    void textureOfOnlyTinySpecksStillShowsAtLeastThreePixels() {
        String[] map = new String[16];
        Arrays.fill(map, "................");
        map[2] = "..x....x....x...";
        map[6] = "..x....x....x...";
        map[10] = "..x....x....x...";
        ArgbImage host = SpeckTestTextures.host();
        OreSpeckLayout layout = plan(OreSpeckAnalysis.analyze(
                SpeckTestTextures.ore(host, map, SpeckTestTextures.IRON_PALETTE), host, SpeckTestTextures.IRON_PALETTE
        ));

        assertEquals(9, layout.baseCount());
        assertEquals(3, opaquePixels(layout.renderOre(0.08D)));
    }

    @Test
    void everyStateIsNestedInsideTheNextRicherOne() {
        for (OreSpeckAnalysis analysis : List.of(SpeckTestTextures.exampleAnalysis(), twelveSpecks(), singleLargeSpeck())) {
            OreSpeckLayout layout = plan(analysis);
            int[] previous = null;
            for (int stage = 0; stage < OreVisualStages.COUNT; stage++) {
                int[] current = layout.renderOre(OreVisualStages.fillFactor(stage)).pixels();
                if (previous != null) {
                    for (int index = 0; index < current.length; index++) {
                        if (previous[index] != 0) {
                            assertEquals(previous[index], current[index], "stage " + stage + " pixel " + index);
                        }
                    }
                }
                previous = current;
            }
        }
    }

    @Test
    void overfillAddsMostlySmallExtrasThatMayUseTheWholeTexture() {
        OreSpeckAnalysis analysis = SpeckTestTextures.exampleAnalysis();
        OreSpeckLayout layout = plan(analysis);
        double maximum = OreVisualStages.maximumFillFactor();
        List<OreSpeckLayout.Placement> overfilled = layout.placementsFor(maximum);

        assertTrue(layout.placementsFor(1.3D).size() > analysis.total());
        assertTrue(overfilled.size() > layout.placementsFor(1.3D).size());
        List<OreSpeckLayout.Placement> extras = overfilled.stream().filter(OreSpeckLayout.Placement::extra).toList();
        long small = extras.stream().filter(placement -> placement.speck().type().ordinal() <= OreSpeckType.TYPE_2.ordinal()).count();
        assertTrue(small * 2 > extras.size(), "extras should be mostly type 1-2");
        for (OreSpeckLayout.Placement placement : extras) {
            assertTrue(placement.x() >= 0 && placement.x() + placement.speck().width() <= layout.size());
            assertTrue(placement.y() >= 0 && placement.y() + placement.speck().height() <= layout.size());
        }
    }

    @Test
    void earlyExtrasAreNeverLargerThanTypeTwo() {
        OreSpeckLayout layout = plan(twelveSpecks());
        List<OreSpeckLayout.Placement> extras = layout.placementsFor(1.5D).stream()
                .filter(OreSpeckLayout.Placement::extra)
                .toList();

        assertEquals(6, extras.size());
        assertTrue(extras.stream().allMatch(placement -> placement.speck().type().ordinal() <= OreSpeckType.TYPE_2.ordinal()));
    }

    @Test
    void fewOrDominantSpecksFallBackToNoiseClipping() {
        OreSpeckLayout layout = plan(singleLargeSpeck());
        int full = opaquePixels(layout.renderOre(1.0D));
        int half = opaquePixels(layout.renderOre(0.5D));

        assertTrue(layout.noiseMode());
        assertEquals(1, layout.placementsFor(0.5D).size());
        assertEquals(Math.round(full * 0.5D), half);
        assertTrue(opaquePixels(layout.renderOre(0.08D)) >= OreSpeckType.TYPE_2.minimumSize());
    }

    @Test
    void touchingBlocksInOneWallGetDifferentArrangements() {
        int face = 3;
        int[][] planes = {{1, 0, 0, 0, 1, 0}, {1, 0, 0, 0, 0, 1}, {0, 1, 0, 0, 0, 1}};
        for (int[] plane : planes) {
            for (int a = -20; a < 20; a++) {
                for (int b = -20; b < 20; b++) {
                    int x = plane[0] * a + plane[3] * b;
                    int y = plane[1] * a + plane[4] * b;
                    int z = plane[2] * a + plane[5] * b;
                    if (Math.floorMod(x, 8) == 7 || Math.floorMod(y, 8) == 7 || Math.floorMod(z, 8) == 7) {
                        continue;
                    }
                    int variant = OreSpeckLayout.variantFor(x, y, z, face, 8);
                    for (int[] step : new int[][] {{1, 0}, {0, 1}, {1, 1}, {1, -1}}) {
                        int nx = x + plane[0] * step[0] + plane[3] * step[1];
                        int ny = y + plane[1] * step[0] + plane[4] * step[1];
                        int nz = z + plane[2] * step[0] + plane[5] * step[1];
                        if (Math.floorDiv(nx, 8) != Math.floorDiv(x, 8) || Math.floorDiv(ny, 8) != Math.floorDiv(y, 8)
                                || Math.floorDiv(nz, 8) != Math.floorDiv(z, 8)) {
                            continue;
                        }
                        assertTrue(variant != OreSpeckLayout.variantFor(nx, ny, nz, face, 8), x + "," + y + "," + z);
                    }
                }
            }
        }
    }

    @Test
    void fiveBlocksInARowAllLookDifferent() {
        java.util.Set<Integer> variants = new java.util.HashSet<>();
        for (int x = 0; x < 5; x++) {
            variants.add(OreSpeckLayout.variantFor(x, 12, -40, 2, 8));
        }
        assertEquals(5, variants.size());
    }

    @Test
    void traceHasTheOrePixelsWithoutAnyColor() {
        OreSpeckLayout layout = plan(SpeckTestTextures.exampleAnalysis());
        int[] ore = layout.renderOre(1.0D).pixels();
        int[] trace = layout.renderTrace(1.0D).pixels();
        for (int index = 0; index < ore.length; index++) {
            assertEquals(ArgbImage.alpha(ore[index]), ArgbImage.alpha(trace[index]));
            assertEquals(ArgbImage.red(trace[index]), ArgbImage.green(trace[index]));
            assertEquals(ArgbImage.green(trace[index]), ArgbImage.blue(trace[index]));
        }
    }

    @Test
    void differentSeedsArrangeTheSameSpecksDifferently() {
        OreSpeckAnalysis analysis = SpeckTestTextures.exampleAnalysis();
        double maximum = OreVisualStages.maximumFillFactor();
        java.util.Set<String> arrangements = new java.util.HashSet<>();
        for (long seed = 0; seed < 8; seed++) {
            OreSpeckLayout layout = OreSpeckLayout.plan(analysis, seed, maximum);
            assertEquals(analysis.total(), layout.placementsFor(1.0D).size());
            arrangements.add(Arrays.toString(layout.renderOre(1.0D).pixels()));
        }
        assertEquals(8, arrangements.size());
    }

    @Test
    void sameSeedGivesTheSameLayout() {
        OreSpeckAnalysis analysis = SpeckTestTextures.exampleAnalysis();
        for (int stage = 0; stage < OreVisualStages.COUNT; stage++) {
            double fill = OreVisualStages.fillFactor(stage);
            assertArrayEquals(plan(analysis).renderOre(fill).pixels(), plan(analysis).renderOre(fill).pixels());
        }
    }

    @Test
    void smallTemplatesAreCutFromLargerSpecksWhenTheOriginalHasNone() {
        OreSpeckAnalysis analysis = singleLargeSpeck();
        Random random = new Random(SEED);
        for (int attempt = 0; attempt < 20; attempt++) {
            OreSpeck small = OreSpeckLayout.templateOfType(analysis, OreSpeckType.TYPE_1, random);
            assertEquals(OreSpeckType.TYPE_1, small.type());
            boolean[] mask = small.shapeMask();
            ArgbImage image = new ArgbImage(small.width(), small.height(), new int[small.width() * small.height()]);
            assertEquals(1, OreSpeckAnalysis.connectedSpecks(image, mask).size());
        }
    }

    @Test
    void emptyAnalysisRendersNothing() {
        OreSpeckLayout layout = plan(OreSpeckAnalysis.empty());
        assertEquals(0, opaquePixels(layout.renderOre(OreVisualStages.maximumFillFactor())));
    }

    private static OreSpeckLayout plan(OreSpeckAnalysis analysis) {
        return OreSpeckLayout.plan(analysis, SEED, OreVisualStages.maximumFillFactor());
    }

    private static int opaquePixels(ArgbImage image) {
        return (int) Arrays.stream(image.pixels()).filter(color -> ArgbImage.alpha(color) != 0).count();
    }

    /** Twelve specks: 6 of type 1, 4 of type 2, 2 of type 3. */
    private static OreSpeckAnalysis twelveSpecks() {
        String[] map = {
                "a...a...a...a...",
                "................",
                "a...a...........",
                "................",
                "bb...bb...bb....",
                "b.....b....b....",
                "................",
                "bb..............",
                "bb..............",
                "................",
                "ccc....ccc......",
                "cc......ccc.....",
                "................",
                "................",
                "................",
                "................"
        };
        ArgbImage host = SpeckTestTextures.host();
        OreSpeckAnalysis analysis = OreSpeckAnalysis.analyze(
                SpeckTestTextures.ore(host, map, SpeckTestTextures.IRON_PALETTE), host, SpeckTestTextures.IRON_PALETTE
        );
        assertEquals(12, analysis.total());
        assertEquals(6, analysis.count(OreSpeckType.TYPE_1));
        assertEquals(4, analysis.count(OreSpeckType.TYPE_2));
        assertEquals(2, analysis.count(OreSpeckType.TYPE_3));
        return analysis;
    }

    private static OreSpeckAnalysis singleLargeSpeck() {
        String[] map = new String[16];
        Arrays.fill(map, "................");
        map[5] = "....xxxxx.......";
        map[6] = "...xxxxxxx......";
        map[7] = "....xxxxx.......";
        ArgbImage host = SpeckTestTextures.host();
        return OreSpeckAnalysis.analyze(
                SpeckTestTextures.ore(host, map, SpeckTestTextures.IRON_PALETTE), host, SpeckTestTextures.IRON_PALETTE
        );
    }
}
