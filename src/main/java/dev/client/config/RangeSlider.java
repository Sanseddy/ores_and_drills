package dev.client.config;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Slider over [min, max] snapped to {@code step}, reporting the snapped value on every change. */
final class RangeSlider extends AbstractSliderButton {
    private final double min;
    private final double max;
    private final double step;
    private final DoubleFunction<Component> format;
    private final DoubleConsumer onChange;

    RangeSlider(
            int width,
            double min,
            double max,
            double step,
            double initial,
            DoubleFunction<Component> format,
            DoubleConsumer onChange) {
        super(0, 0, width, 20, Component.empty(), 0.0D);
        this.min = min;
        this.max = max;
        this.step = step;
        this.format = format;
        this.onChange = onChange;
        this.value = (snap(initial) - min) / (max - min);
        updateMessage();
    }

    double current() {
        return snap(min + value * (max - min));
    }

    private double snap(double raw) {
        double snapped = min + Math.round((raw - min) / step) * step;
        // Rounds away float noise such as 0.07000000000000001 from repeated step multiplication.
        return Mth.clamp(Math.round(snapped * 1_000_000.0D) / 1_000_000.0D, min, max);
    }

    @Override
    protected void updateMessage() {
        setMessage(format.apply(current()));
    }

    @Override
    protected void applyValue() {
        onChange.accept(current());
    }
}
