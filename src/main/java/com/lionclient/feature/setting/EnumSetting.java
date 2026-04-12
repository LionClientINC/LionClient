package com.lionclient.feature.setting;

import com.lionclient.config.ConfigManager;

public final class EnumSetting<T extends Enum<T>> extends Setting {
    private final T[] values;
    private int index;

    public EnumSetting(String name, T[] values, T initial) {
        super(name);
        this.values = values;
        this.index = indexOf(initial);
    }

    public T getValue() {
        return values[index];
    }

    public void setValue(T value) {
        this.index = indexOf(value);
        ConfigManager.saveActiveConfig();
    }

    public boolean setValueByName(String name) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].name().equalsIgnoreCase(name)) {
                index = i;
                ConfigManager.saveActiveConfig();
                return true;
            }
        }
        return false;
    }

    public void cycleForward() {
        index = (index + 1) % values.length;
        ConfigManager.saveActiveConfig();
    }

    public void cycleBackward() {
        index = (index - 1 + values.length) % values.length;
        ConfigManager.saveActiveConfig();
    }

    @Override
    public String getValueText() {
        return getValue().toString();
    }

    private int indexOf(T initial) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == initial) {
                return i;
            }
        }
        return 0;
    }
}
