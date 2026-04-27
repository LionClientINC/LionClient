package com.lionclient.feature.setting;

import com.lionclient.config.ConfigManager;
import java.math.BigDecimal;
import java.math.RoundingMode;

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
        this.value = clampToRange(value);
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
        value = clampToRange(value + step);
        ConfigManager.saveActiveConfig();
    }

    public void decrement() {
        value = clampToRange(value - step);
        ConfigManager.saveActiveConfig();
    }

    public void setValue(double value) {
        setValue(value, true);
    }

    public void setValue(double value, boolean save) {
        this.value = clampToRange(value);
        if (save) {
            ConfigManager.saveActiveConfig();
        }
    }

    public void setManualValue(double value) {
        setManualValue(value, true);
    }

    public void setManualValue(double value, boolean save) {
        this.value = clampManual(value);
        if (save) {
            ConfigManager.saveActiveConfig();
        }
    }

    @Override
    public String getValueText() {
        String text = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return text.indexOf('.') >= 0 ? text : text + ".0";
    }

    private double clampToRange(double input) {
        double clamped = Math.max(min, Math.min(max, input));
        return roundToStep(clamped);
    }

    private double clampManual(double input) {
        return roundToStep(Math.max(min, input));
    }

    private double roundToStep(double input) {
        BigDecimal stepped = BigDecimal.valueOf(input)
            .divide(BigDecimal.valueOf(step), 0, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(step));
        return stepped.doubleValue();
    }
}
