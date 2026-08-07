package dev.client.ore;

final class DepletionOpacity {
    private static final int DARK_BRIGHTNESS_LIMIT = 64;
    private static final int MID_BRIGHTNESS = 128;
    private static final int DARK_VERTEX_ALPHA = 255;
    private static final int MID_VERTEX_ALPHA = 160;
    private static final int LIGHT_VERTEX_ALPHA = 90;

    private DepletionOpacity() {
    }

    /**
     * Keeps the depletion trace dense on dark host rock and gradually softens it on light rock.
     * The texture's own per-pixel alpha still supplies the crack detail inside this envelope.
     */
    static int vertexAlpha(int averageBrightness) {
        int clampedBrightness = Math.max(0, Math.min(255, averageBrightness));
        if (clampedBrightness <= DARK_BRIGHTNESS_LIMIT) {
            return DARK_VERTEX_ALPHA;
        }
        if (clampedBrightness <= MID_BRIGHTNESS) {
            return interpolate(
                    clampedBrightness,
                    DARK_BRIGHTNESS_LIMIT,
                    MID_BRIGHTNESS,
                    DARK_VERTEX_ALPHA,
                    MID_VERTEX_ALPHA
            );
        }
        return interpolate(
                clampedBrightness,
                MID_BRIGHTNESS,
                255,
                MID_VERTEX_ALPHA,
                LIGHT_VERTEX_ALPHA
        );
    }

    private static int interpolate(int value, int fromValue, int toValue, int fromAlpha, int toAlpha) {
        double progress = (double)(value - fromValue) / (toValue - fromValue);
        return (int)Math.round(fromAlpha + (toAlpha - fromAlpha) * progress);
    }
}
