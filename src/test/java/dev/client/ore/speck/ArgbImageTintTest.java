package dev.client.ore.speck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ArgbImageTintTest {
    @Test
    void tintMultipliesColorAndKeepsAlpha() {
        // A grey material-set pixel colored like GregTech copper (0xFF6400) keeps its shading and
        // transparency.
        assertEquals(0x80804000, ArgbImage.multiply(0x80808080, 0xFF8000));
        assertEquals(0xFFFF6400, ArgbImage.multiply(0xFFFFFFFF, 0xFF6400));
        assertEquals(0x00000000, ArgbImage.multiply(0x00000000, 0xFF6400));
    }

    @Test
    void whiteOrMissingTintLeavesTheImageUntouched() {
        ArgbImage image = new ArgbImage(1, 1, new int[] {0xFF123456});

        assertSame(image, image.tinted(0xFFFFFF));
        assertSame(image, image.tinted(-1));
        assertEquals(0xFF091A2B, image.tinted(0x808080).get(0, 0));
    }

    @Test
    void stackedLayersBlendLikeTranslucentModelLayers() {
        ArgbImage pattern =
                new ArgbImage(2, 2, new int[] {0xFFFF0000, 0x00000000, 0x00000000, 0xFFFF0000});
        ArgbImage secondary =
                new ArgbImage(2, 2, new int[] {0x00000000, 0xFF00FF00, 0x00000000, 0x800000FF});

        ArgbImage stacked = pattern.over(secondary);

        assertEquals(0xFFFF0000, stacked.get(0, 0));
        assertEquals(0xFF00FF00, stacked.get(1, 0));
        assertEquals(0x00000000, stacked.get(0, 1));
        assertEquals(0xFF7F0080, stacked.get(1, 1));
    }

    @Test
    void stackedLayersOfDifferentResolutionsShareTheBottomGrid() {
        ArgbImage bottom =
                new ArgbImage(2, 2, new int[] {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000});
        ArgbImage top = new ArgbImage(4, 4, new int[16]);

        assertEquals(2, bottom.over(top).width());
    }
}
