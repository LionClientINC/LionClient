package com.lionclient.feature.setting;

import java.util.function.BooleanSupplier;

public abstract class Setting {
    private final String name;
    private BooleanSupplier visibility = new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
            return true;
        }
    };

    protected Setting(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean isVisible() {
        return visibility.getAsBoolean();
    }

    public void setVisibility(BooleanSupplier visibility) {
        this.visibility = visibility == null ? this.visibility : visibility;
    }

    public abstract String getValueText();
}
