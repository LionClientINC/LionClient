package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.NumberSetting;
import org.lwjgl.input.Keyboard;

public final class ClickGuiModule extends Module {
    private static ClickGuiModule instance;

    private final NumberSetting red = new NumberSetting("Red", 0, 255, 5, 139);
    private final NumberSetting green = new NumberSetting("Green", 0, 255, 5, 0);
    private final NumberSetting blue = new NumberSetting("Blue", 0, 255, 5, 0);

    public ClickGuiModule() {
        super("ClickGUI", "Customize the ClickGUI accent color.", Category.CLIENT, Keyboard.KEY_NONE);
        instance = this;
        addSetting(red);
        addSetting(green);
        addSetting(blue);
    }

    @Override
    public void toggle() {
    }

    public static int getAccentColor() {
        if (instance == null) {
            return 0x8B0000;
        }
        return ((instance.red.getValue() & 255) << 16)
            | ((instance.green.getValue() & 255) << 8)
            | (instance.blue.getValue() & 255);
    }
}
