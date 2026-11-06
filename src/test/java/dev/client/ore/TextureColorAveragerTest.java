package dev.client.ore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextureColorAveragerTest {
    @Test
    void ignoresTransparentAndRelativeDarkestPixels() {
        int[] pixels = {
            0x00112233, 0xFF030405, 0xFF306090, 0xFF306090, 0xFF90C0F0,
        };

        TextureColorAverager.Analysis analysis = TextureColorAverager.analyze(pixels, 16);

        assertArrayEquals(new int[] {0x306090, 0x90C0F0}, analysis.palette());
        assertEquals(0x5080B0, analysis.averageRgb());
    }

    @Test
    void weightsEveryPaletteColorByItsFrequency() {
        int[] pixels = {
            0xFFCC3300, 0xFFCC3300, 0xFFCC3300, 0xFF0066CC,
        };

        TextureColorAverager.Analysis analysis = TextureColorAverager.analyze(pixels, 16);

        assertEquals(0x993F33, analysis.averageRgb());
    }

    @Test
    void returnsNoColorWhenNoVisiblePixelsRemain() {
        TextureColorAverager.Analysis analysis =
                TextureColorAverager.analyze(new int[] {0x00000000, 0x7FFFFFFF}, 16);

        assertEquals(-1, analysis.averageRgb());
        assertEquals(0, analysis.palette().length);
    }
}
