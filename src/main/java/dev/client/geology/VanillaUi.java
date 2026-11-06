package dev.client.geology;

import dev.client.geology.layout.Rect;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The survey screens' frames drawn the way vanilla container textures look: a light grey panel with
 * a white top-left bevel and a dark bottom-right one inside a black rounded outline, sunken
 * slot-like insets, and bars sunk into the panel like the furnace's. Drawn from fills so they scale
 * to any size without stretching a texture.
 */
public final class VanillaUi {
    public static final int PANEL = 0xFFC6C6C6;
    public static final int TEXT = 0xFF404040;
    public static final int TEXT_DIM = 0xFF6B6B6B;
    public static final int TEXT_ACCENT = 0xFF1F3F7F;
    public static final int TEXT_WARNING = 0xFF8B2E00;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int SHADOW = 0xFF555555;
    private static final int DARK = 0xFF373737;
    private static final int OUTLINE = 0xFF000000;

    private VanillaUi() {}

    /** A raised panel like an inventory background. */
    public static void panel(GuiGraphics graphics, Rect r) {
        if (r.width() < 6 || r.height() < 6) {
            return;
        }
        int x = r.x();
        int y = r.y();
        int right = r.right();
        int bottom = r.bottom();
        // Black outline with the corner pixels cut, as on vanilla textures.
        graphics.fill(x + 2, y, right - 3, y + 1, OUTLINE);
        graphics.fill(x + 2, bottom - 1, right - 2, bottom, OUTLINE);
        graphics.fill(x, y + 2, x + 1, bottom - 3, OUTLINE);
        graphics.fill(right - 1, y + 3, right, bottom - 2, OUTLINE);
        graphics.fill(x + 1, y + 1, x + 2, y + 2, OUTLINE);
        graphics.fill(right - 3, y + 1, right - 2, y + 2, OUTLINE);
        graphics.fill(right - 2, y + 2, right - 1, y + 3, OUTLINE);
        graphics.fill(x + 1, bottom - 3, x + 2, bottom - 2, OUTLINE);
        graphics.fill(x + 2, bottom - 2, x + 3, bottom - 1, OUTLINE);
        graphics.fill(right - 2, bottom - 2, right - 1, bottom - 1, OUTLINE);
        // Body and bevels.
        graphics.fill(x + 1, y + 2, right - 1, bottom - 2, PANEL);
        graphics.fill(x + 2, y + 1, right - 2, bottom - 1, PANEL);
        graphics.fill(x + 1, y + 1, right - 3, y + 3, LIGHT);
        graphics.fill(x + 1, y + 1, x + 3, bottom - 3, LIGHT);
        graphics.fill(x + 3, bottom - 3, right - 1, bottom - 1, SHADOW);
        graphics.fill(right - 3, y + 3, right - 1, bottom - 1, SHADOW);
        graphics.fill(right - 3, y + 1, right - 2, y + 2, PANEL);
        graphics.fill(x + 1, bottom - 2, x + 2, bottom - 1, PANEL);
    }

    /** A sunken area like a slot: dark top-left edge, white bottom-right edge. */
    public static void inset(GuiGraphics graphics, Rect r, int fill) {
        graphics.fill(r.x() - 1, r.y() - 1, r.right(), r.y(), DARK);
        graphics.fill(r.x() - 1, r.y(), r.x(), r.bottom(), DARK);
        graphics.fill(r.x(), r.bottom(), r.right() + 1, r.bottom() + 1, LIGHT);
        graphics.fill(r.right(), r.y() - 1, r.right() + 1, r.bottom(), LIGHT);
        graphics.fill(r.x(), r.y(), r.right(), r.bottom(), fill);
    }

    /** A thin separator line with the panel's bevel. */
    public static void separator(GuiGraphics graphics, int x, int y, int width) {
        graphics.fill(x, y, x + width, y + 1, SHADOW);
        graphics.fill(x, y + 1, x + width, y + 2, LIGHT);
    }

    /** A sunken bar filled to {@code fraction} with {@code color}. */
    public static void bar(GuiGraphics graphics, Rect r, double fraction, int color) {
        inset(graphics, r, 0xFF8B8B8B);
        int filled = (int) Math.round(r.width() * Math.max(0.0D, Math.min(1.0D, fraction)));
        if (filled > 0) {
            graphics.fill(r.x(), r.y(), r.x() + filled, r.bottom(), color);
            graphics.fill(r.x(), r.y(), r.x() + filled, r.y() + 1, brighter(color));
        }
    }

    private static int brighter(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 48);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 48);
        int b = Math.min(255, (argb & 0xFF) + 48);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
