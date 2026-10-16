package dev.client.map;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreMapStyleTest {
    @Test
    void factorioResourcesUseFactorioMapColours() {
        assertEquals(0x6A8694, OreMapPalette.materialColor("iron"));
        assertEquals(0xCD6337, OreMapPalette.materialColor("deepslate_copper_ore"));
        assertEquals(0x00B300, OreMapPalette.materialColor("uranium"));
    }

    @Test
    void specificMaterialNamesWinOverShorterOnes() {
        assertEquals(0xC4241B, OreMapPalette.materialColor("deepslate_redstone_ore"));
        assertEquals(0xB9D5F0, OreMapPalette.materialColor("certus_quartz_ore"));
        assertEquals(0x6E4C3C, OreMapPalette.materialColor("ancient_debris"));
    }

    @Test
    void materialsInsideOtherWordsAreNotMistaken() {
        assertEquals(0xA9DFF2, OreMapPalette.materialColor("platinum_ore"));
        assertEquals(0x6A8694, OreMapPalette.materialColor("basalt_iron_ore"));
        assertNull(OreMapPalette.materialColor("basalt_ore"));
        assertEquals(0x6A8694, OreMapPalette.materialColor("ironore"));
    }

    @Test
    void unknownMaterialsFallThrough() {
        assertNull(OreMapPalette.materialColor("mystery_rock"));
    }

    @Test
    void textureFallbackPicksTheMostColourfulUsableTone() {
        int[] palette = {0xFF101010, 0xFFF8F8F8, 0xFF807A70, 0xFF3FA040, 0xFF909090};

        assertEquals(0x3FA040, OreMapPalette.mostVivid(palette));
        assertNull(OreMapPalette.mostVivid(new int[]{0xFF050505, 0xFFFAFAFA}));
    }

    @Test
    void depletedTilesAreDarkerButKeepTheResourceHue() {
        int full = OreMapPalette.shade(0xCD6337, 1_000, 1_000);
        int depleted = OreMapPalette.shade(0xCD6337, 50, 1_000);

        assertEquals(0xFF, full >>> 24);
        assertEquals(0xCD6337, full & 0xFFFFFF);
        assertTrue((depleted >> 16 & 255) < (full >> 16 & 255));
        assertTrue((depleted >> 16 & 255) > (depleted & 255));
    }

    @Test
    void amountsAreGroupedLikeFactorioTooltips() {
        assertEquals("1 234 567", OreMapPalette.formatAmount(1_234_567L));
        assertEquals("950", OreMapPalette.formatAmount(950L));
    }

    @Test
    void zoomingOutPicksTilesThatStayAtLeastOnePixelWide() {
        assertEquals(0, OreMapTiling.levelFor(4.0D));
        assertEquals(0, OreMapTiling.levelFor(1.0D));
        assertEquals(1, OreMapTiling.levelFor(0.5D));
        assertEquals(2, OreMapTiling.levelFor(0.3D));
        assertEquals(OreMapTiling.MAX_LEVEL, OreMapTiling.levelFor(0.0001D));
    }

    @Test
    void mergedTilesCoverEveryCellOnAGridAlignedToTheCentre() {
        // A 4x4 block square straddling the centre, offsets -2..1.
        int[] xs = new int[16];
        int[] zs = new int[16];
        long[] amounts = new long[16];
        for (int index = 0; index < 16; index++) {
            xs[index] = index % 4 - 2;
            zs[index] = index / 4 - 2;
            amounts[index] = 10;
        }

        OreMapTiling.Tiles single = OreMapTiling.tile(xs, zs, amounts, amounts, 1, 0x6A8694);
        OreMapTiling.Tiles merged = OreMapTiling.tile(xs, zs, amounts, amounts, 2, 0x6A8694);

        assertEquals(16, single.size());
        assertEquals(4, merged.size());
        Set<Long> corners = new HashSet<>();
        for (int index = 0; index < merged.size(); index++) {
            assertEquals(0, Math.floorMod(merged.xs()[index], 2));
            assertEquals(0, Math.floorMod(merged.zs()[index], 2));
            corners.add((long) merged.xs()[index] << 32 | merged.zs()[index] & 0xFFFFFFFFL);
        }
        assertTrue(corners.contains(-2L << 32 | -2 & 0xFFFFFFFFL));
        assertTrue(corners.contains(0L));
    }

    @Test
    void mergedTilesShadeByTheirCombinedDepletion() {
        int[] xs = {0, 1};
        int[] zs = {0, 0};
        OreMapTiling.Tiles tiles = OreMapTiling.tile(xs, zs, new long[]{0, 100}, new long[]{100, 100}, 2, 0xCD6337);

        assertEquals(1, tiles.size());
        assertEquals(OreMapPalette.shade(0xCD6337, 100, 200), tiles.colors()[0]);
    }

    @Test
    void rotatedTransformTurnsEastIntoSouthAtNinetyDegrees() {
        OreMapTransform transform = OreMapTransform.rotated(2.0D, Math.PI / 2.0D, 10.0D, 20.0D);

        assertEquals(10.0D, transform.x(1.0D, 0.0D), 1.0E-9D);
        assertEquals(22.0D, transform.y(1.0D, 0.0D), 1.0E-9D);
        assertEquals(2.0D, transform.pixelsPerBlock(), 1.0E-9D);
    }

    @Test
    void xaeroMinimapRotationMatchesTheWayXaeroPlacesElements() {
        // Xaero places an element at (ps*dx - pc*dz, pc*dx + ps*dz) * zoom with ps = sin(pi - a), pc = cos(pi - a).
        double angle = Math.toRadians(37.0D);
        double ps = Math.sin(Math.PI - angle);
        double pc = Math.cos(Math.PI - angle);
        double zoom = 3.0D;
        OreMapTransform transform = new OreMapTransform(ps * zoom, -pc * zoom, 0.0D, pc * zoom, ps * zoom, 0.0D);

        double dx = 5.0D;
        double dz = -2.0D;
        assertEquals((ps * dx - pc * dz) * zoom, transform.x(dx, dz), 1.0E-9D);
        assertEquals((pc * dx + ps * dz) * zoom, transform.y(dx, dz), 1.0E-9D);
        assertEquals(zoom, transform.pixelsPerBlock(), 1.0E-9D);
    }
}
