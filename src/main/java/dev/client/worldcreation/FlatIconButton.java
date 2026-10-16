package dev.client.worldcreation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Square icon button drawn flat, matching the drag grip so a row's controls read as one group. */
final class FlatIconButton extends Button {
    static final int SIZE = 20;
    static final int BACKGROUND = 0x40FFFFFF;
    static final int BACKGROUND_HOVERED = 0x80FFFFFF;
    static final int ICON = 0xFFC0C0C0;
    static final int ICON_HOVERED = 0xFFFFFFFF;

    FlatIconButton(Component icon, Component tooltip, OnPress onPress) {
        super(0, 0, SIZE, SIZE, icon, onPress, DEFAULT_NARRATION);
        setTooltip(Tooltip.create(tooltip));
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        draw(guiGraphics, getX(), getY(), getMessage(), active && isHoveredOrFocused());
    }

    /** Also used for the copy of a row that follows the cursor while it is dragged. */
    static void draw(GuiGraphics guiGraphics, int x, int y, Component icon, boolean hovered) {
        guiGraphics.fill(x, y, x + SIZE, y + SIZE, hovered ? BACKGROUND_HOVERED : BACKGROUND);
        var font = Minecraft.getInstance().font;
        guiGraphics.drawString(font, icon, x + (SIZE - font.width(icon) + 1) / 2, y + (SIZE - font.lineHeight) / 2 + 1,
                hovered ? ICON_HOVERED : ICON, false);
    }
}
