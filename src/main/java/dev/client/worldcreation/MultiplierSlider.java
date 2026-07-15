package dev.client.worldcreation;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * A discrete multiplier slider. The vanilla [0,1] slider range is snapped onto fixed percentage steps,
 * with 100% placed at the visual center of the bar.
 * Pass a {@code null} label to show just the value (e.g. for a table row under an already-labeled column header).
 */
public final class MultiplierSlider extends AbstractSliderButton {
    private static final double[] MULTIPLIERS = {
            0.17D, 0.25D, 0.33D, 0.50D, 0.75D, 1.00D,
            1.33D, 1.50D, 2.00D, 3.00D, 4.00D, 6.00D
    };
    private static final int CENTER_INDEX = 5;

    @Nullable
    private final Component label;

    public MultiplierSlider(int x, int y, int width, int height, @Nullable Component label, double min, double max, double initial) {
        super(x, y, width, height, Component.empty(), positionForIndex(closestMultiplierIndex(initial)));
        this.label = label;
        updateMessage();
    }

    public double multiplier() {
        return MULTIPLIERS[closestPositionIndex(value)];
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        setValueFromMouse(mouseX);
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        setValueFromMouse(mouseX);
    }

    private static int closestMultiplierIndex(double multiplier) {
        int bestIndex = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int index = 0; index < MULTIPLIERS.length; index++) {
            double distance = Math.abs(MULTIPLIERS[index] - multiplier);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static int closestPositionIndex(double position) {
        int bestIndex = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int index = 0; index < MULTIPLIERS.length; index++) {
            double distance = Math.abs(positionForIndex(index) - position);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static double positionForIndex(int index) {
        if (index <= CENTER_INDEX) {
            return index / (double)(CENTER_INDEX * 2);
        }
        return 0.5D + (index - CENTER_INDEX) * (0.5D / (MULTIPLIERS.length - CENTER_INDEX - 1));
    }

    private static String percentText(double multiplier) {
        return Integer.toString((int)Math.round(multiplier * 100.0D)) + "%";
    }

    private void setValueFromMouse(double mouseX) {
        double position = (mouseX - getX()) / (double)Math.max(1, width);
        int index = closestPositionIndex(Math.max(0.0D, Math.min(1.0D, position)));
        this.value = positionForIndex(index);
        updateMessage();
        applyValue();
    }

    @Override
    protected void updateMessage() {
        String valueText = percentText(multiplier());
        setMessage(label == null ? Component.empty() : label);
        setTooltip(Tooltip.create(Component.literal(valueText)));
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int centerY = y + height / 2;
        int knobWidth = 6;
        int knobHeight = 13;
        int knobCenterX = x + Math.round((float)(value * width));
        int knobX = Math.max(x, Math.min(x + width - knobWidth, knobCenterX - knobWidth / 2));
        int knobY = centerY - knobHeight / 2;
        int trackColor = isHoveredOrFocused() ? 0xFFE0E0E0 : 0xFFB0B0B0;
        int knobColor = 0xFFE0E0E0;

        guiGraphics.fill(x, centerY, knobX, centerY + 1, trackColor);
        guiGraphics.fill(knobX + knobWidth, centerY, x + width, centerY + 1, trackColor);
        guiGraphics.fill(knobX, knobY, knobX + knobWidth, knobY + knobHeight, knobColor);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!active) {
            return false;
        }

        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            moveByStep(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            moveByStep(1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void moveByStep(int delta) {
        int index = Math.max(0, Math.min(MULTIPLIERS.length - 1, closestPositionIndex(value) + delta));
        this.value = positionForIndex(index);
        updateMessage();
        applyValue();
    }

    @Override
    protected void applyValue() {
        this.value = positionForIndex(closestPositionIndex(value));
    }
}
