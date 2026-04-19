package com.lionclient.feature.setting;

import com.lionclient.config.ConfigManager;
import java.util.Locale;

public final class DecimalSetting extends Setting {
    private final double min;
    private final double max;
    private final double step;
    private double value;

    public DecimalSetting(String name, double min, double max, double step, double value) {
        super(name);
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = clamp(value);
    }

    public double getValue() {
        return value;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getStep() {
        return step;
    }

    public void increment() {
        value = clamp(value + step);
        ConfigManager.saveActiveConfig();
    }

    public void decrement() {
        value = clamp(value - step);
        ConfigManager.saveActiveConfig();
    }

    public void setValue(double value) {
        setValue(value, true);
    }

    public void setValue(double value, boolean save) {
        this.value = clamp(value);
        if (save) {
            ConfigManager.saveActiveConfig();
        }
    }

    @Override
    public String getValueText() {
        return String.format(Locale.US, "%.1f", value);
    }

    private double clamp(double input) {
        double clamped = Math.max(min, Math.min(max, input));
        return Math.round(clamped / step) * step;
    }
}
