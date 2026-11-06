package dev.client.geology;

import dev.client.geology.layout.TextMeasure;
import net.minecraft.client.gui.Font;

/** {@link TextMeasure} of the game font. */
public record FontMeasure(Font font) implements TextMeasure {
    @Override
    public int width(String text) {
        return font.width(text);
    }

    @Override
    public int lineHeight() {
        return font.lineHeight;
    }
}
