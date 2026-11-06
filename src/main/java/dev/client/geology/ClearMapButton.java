package dev.client.geology;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Small icon button drawn over the 3D view: a wall brush in the same translucent blue as its frame,
 * with no background, so the point cloud stays visible behind it.
 */
public final class ClearMapButton extends AbstractButton {
    public static final int SIZE = 16;

    private static final String[] ICON = {
        "....##....",
        "....##....",
        "....##....",
        "....##....",
        "....##....",
        "...####...",
        "##########",
        "##########",
        "##########",
        "##########",
        "##########",
        "##########",
    };

    private static final int OUTLINE = 0x8034579E;
    private static final int OUTLINE_HOVER = 0xC05A8FE6;
    private static final int FILL_HOVER = 0x302A5AA0;

    private final Runnable action;

    public ClearMapButton(Component message, Runnable action) {
        super(0, 0, SIZE, SIZE, message);
        this.action = action;
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean lit = active && isHoveredOrFocused();
        if (lit) {
            graphics.fill(getX(), getY(), getX() + width, getY() + height, FILL_HOVER);
        }
        int frame = lit ? OUTLINE_HOVER : OUTLINE;
        graphics.renderOutline(getX(), getY(), width, height, frame);
        // The frame itself never dims, so a disabled button is told apart by a fainter icon.
        int icon = active ? frame : (frame >>> 25) << 24 | (frame & 0xFFFFFF);
        int left = getX() + (width - ICON[0].length()) / 2;
        int top = getY() + (height - ICON.length) / 2;
        for (int row = 0; row < ICON.length; row++) {
            for (int column = 0; column < ICON[row].length(); column++) {
                if (ICON[row].charAt(column) != '#') {
                    continue;
                }
                int x = left + column;
                int y = top + row;
                graphics.fill(x, y, x + 1, y + 1, icon);
            }
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
