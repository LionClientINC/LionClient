package com.lionclient.feature.setting;

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
        this.value = clamp(value);
    }

    public int getValue() {
        return value;
    }

    public void increment() {
        value = clamp(value + step);
    }

    public void decrement() {
        value = clamp(value - step);
    }

    public void setValue(int value) {
        this.value = clamp(value);
    }

    @Override
    public String getValueText() {
        return Integer.toString(value);
    }

    private int clamp(int input) {
        return Math.max(min, Math.min(max, input));
    }
}
