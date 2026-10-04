package dev.client.ore.speck;

import dev.world.level.levelgen.OreVisualStages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Resource pack support: high resolution textures and overlay-style ore textures. */
class OreSpeckResolutionTest {
    @Test
    void highResolutionTextureKeepsTheSameSpecksAndTypes() {
        ArgbImage host = SpeckTestTextures.host();
        ArgbImage ore = SpeckTestTextures.ore(host, SpeckTestTextures.EXAMPLE_MAP, SpeckTestTextures.IRON_PALETTE);
        OreSpeckAnalysis analysis = OreSpeckAnalysis.analyze(
                upscale(ore, 2), upscale(host, 2), SpeckTestTextures.IRON_PALETTE, 32
        );

        assertEquals(32, analysis.resolution());
        assertEquals(11, analysis.total());
        assertEquals(5, analysis.count(OreSpeckType.TYPE_1));
        assertEquals(3, analysis.count(OreSpeckType.TYPE_2));
        assertEquals(2, analysis.count(OreSpeckType.TYPE_3));
        assertEquals(1, analysis.count(OreSpeckType.TYPE_4));
    }

    @Test
    void sixteenPixelOreInAThirtyTwoPixelPackIsScaledUp() {
        OreSpeckAnalysis analysis = OreSpeckAnalysis.analyze(
                SpeckTestTextures.ore(SpeckTestTextures.host(), SpeckTestTextures.EXAMPLE_MAP, SpeckTestTextures.IRON_PALETTE),
                SpeckTestTextures.host(),
                SpeckTestTextures.IRON_PALETTE,
                32
        );

        assertEquals(11, analysis.total());
        assertEquals(1, analysis.count(OreSpeckType.TYPE_4));
    }

    @Test
    void layoutIsGeneratedAtTheAnalysisResolutionWithScaledInnerArea() {
        ArgbImage host = SpeckTestTextures.host();
        ArgbImage ore = SpeckTestTextures.ore(host, SpeckTestTextures.EXAMPLE_MAP, SpeckTestTextures.IRON_PALETTE);
        OreSpeckAnalysis analysis = OreSpeckAnalysis.analyze(upscale(ore, 2), upscale(host, 2), SpeckTestTextures.IRON_PALETTE, 32);
        OreSpeckLayout layout = OreSpeckLayout.plan(analysis, 7L, OreVisualStages.maximumFillFactor());

        assertEquals(32, layout.size());
        assertEquals(2, layout.innerMin());
        assertEquals(30, layout.innerMax());
        assertEquals(32, layout.renderOre(1.0D).width());
        assertEquals(32, layout.renderTrace(0.2D).height());
        List<OreSpeckLayout.Placement> normal = layout.placementsFor(1.0D);
        assertEquals(11, normal.size());
        for (OreSpeckLayout.Placement placement : normal) {
            assertTrue(placement.x() >= layout.innerMin() && placement.y() >= layout.innerMin());
            assertTrue(placement.x() + placement.speck().width() <= layout.innerMax());
            assertTrue(placement.y() + placement.speck().height() <= layout.innerMax());
        }
        assertTrue(layout.placementsFor(0.2D).stream().noneMatch(placement -> placement.speck().type() == OreSpeckType.TYPE_1));
    }

    @Test
    void scaledTypeBoundariesAreConsistent() {
        for (int resolution : new int[] {16, 32, 64}) {
            for (OreSpeckType type : OreSpeckType.values()) {
                assertEquals(type, OreSpeckType.forSize(type.minimumPixels(resolution), resolution));
                if (type != OreSpeckType.TYPE_4) {
                    assertEquals(type, OreSpeckType.forSize(type.maximumPixels(resolution), resolution));
                    assertEquals(
                            OreSpeckType.values()[type.ordinal() + 1],
                            OreSpeckType.forSize(type.maximumPixels(resolution) + 1, resolution)
                    );
                }
            }
        }
        assertEquals(OreSpeckType.TYPE_1, OreSpeckType.forSize(4, 32));
    }

    @Test
    void transparentOverlayTextureIsReadAsOreLayer() {
        int[] pixels = SpeckTestTextures.ore(
                SpeckTestTextures.host(), SpeckTestTextures.EXAMPLE_MAP, SpeckTestTextures.IRON_PALETTE
        ).pixels().clone();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (SpeckTestTextures.EXAMPLE_MAP[y].charAt(x) == '.') {
                    pixels[y * 16 + x] = 0;
                }
            }
        }
        OreSpeckAnalysis analysis = OreSpeckAnalysis.analyze(new ArgbImage(16, 16, pixels), null, new int[0]);

        assertEquals(OreSpeckAnalysis.Source.OVERLAY, analysis.source());
        assertEquals(11, analysis.total());
    }

    @Test
    void syntheticTemplatesScaleWithResolution() {
        OreSpeckAnalysis small = OreSpeckAnalysis.synthetic(SpeckTestTextures.IRON_PALETTE, 16);
        OreSpeckAnalysis large = OreSpeckAnalysis.synthetic(SpeckTestTextures.IRON_PALETTE, 32);

        assertEquals(small.total(), large.total());
        assertEquals(small.totalPixels() * 4, large.totalPixels());
        for (OreSpeckType type : OreSpeckType.values()) {
            assertEquals(small.count(type), large.count(type));
        }
    }

    private static ArgbImage upscale(ArgbImage image, int factor) {
        int size = image.width() * factor;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = image.get(x / factor, y / factor);
            }
        }
        return new ArgbImage(size, size, pixels);
    }
}
