package com.lionclient.feature.setting;

import com.lionclient.config.ConfigManager;

public final class NumberSetting extends Setting {
    private final int min;
    private final int max;
    private final int step;
    private int value;

    public NumberSetting(String name, int min, int max, int step, int value) {
        super(name);
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = clampToRange(value);
    }

    public int getValue() {
        return value;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public int getStep() {
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

    public void setValue(int value) {
        setValue(value, true);
    }

    public void setValue(int value, boolean save) {
        this.value = clampToRange(value);
        if (save) {
            ConfigManager.saveActiveConfig();
        }
    }

    public void setManualValue(int value) {
        setManualValue(value, true);
    }

    public void setManualValue(int value, boolean save) {
        this.value = clampManual(value);
        if (save) {
            ConfigManager.saveActiveConfig();
        }
    }

    @Override
    public String getValueText() {
        return Integer.toString(value);
    }

    private int clampToRange(int input) {
        return Math.max(min, Math.min(max, input));
    }

    private int clampManual(int input) {
        return Math.max(min, input);
    }
}
