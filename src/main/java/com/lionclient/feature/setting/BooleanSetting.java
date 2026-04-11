package com.lionclient.feature.setting;

public final class BooleanSetting extends Setting {
    private boolean enabled;

    public BooleanSetting(String name, boolean enabled) {
        super(name);
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        enabled = !enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String getValueText() {
        return enabled ? "ON" : "OFF";
    }
}
