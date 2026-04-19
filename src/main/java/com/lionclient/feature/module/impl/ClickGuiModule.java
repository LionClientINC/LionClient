package com.lionclient.feature.module.impl;

import com.lionclient.LionClient;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import org.lwjgl.input.Keyboard;

public final class ClickGuiModule extends Module {
    private static ClickGuiModule instance;

    private final EnumSetting<GuiStyle> style = new EnumSetting<GuiStyle>("Style", GuiStyle.values(), GuiStyle.MODERN);
    private final NumberSetting red = new NumberSetting("Red", 0, 255, 5, 48);
    private final NumberSetting green = new NumberSetting("Green", 0, 255, 5, 92);
    private final NumberSetting blue = new NumberSetting("Blue", 0, 255, 5, 168);

    public ClickGuiModule() {
        super("ClickGUI", "Open and customize the ClickGUI.", Category.CLIENT, Keyboard.KEY_RSHIFT);
        instance = this;
        addSetting(style);
        addSetting(red);
        addSetting(green);
        addSetting(blue);
    }

    @Override
    public void toggle() {
        LionClient client = LionClient.getInstance();
        if (client != null) {
            client.toggleClickGui();
        }
    }

    @Override
    public boolean canBeUnbound() {
        return false;
    }

    public static int getAccentColor() {
        if (instance == null) {
            return 0x305CA8;
        }
        return ((instance.red.getValue() & 255) << 16)
            | ((instance.green.getValue() & 255) << 8)
            | (instance.blue.getValue() & 255);
    }

    public static GuiStyle getGuiStyle() {
        return instance == null ? GuiStyle.MODERN : instance.style.getValue();
    }

    public static int getLightAccentColor() {
        return 0xB9DEFF;
    }

    public static int getDarkAccentColor() {
        return 0x23497F;
    }

    public static int blendColor(int start, int end, float progress) {
        float amount = Math.max(0.0F, Math.min(1.0F, progress));
        int startR = (start >>> 16) & 255;
        int startG = (start >>> 8) & 255;
        int startB = start & 255;
        int endR = (end >>> 16) & 255;
        int endG = (end >>> 8) & 255;
        int endB = end & 255;
        int red = Math.round(startR + ((endR - startR) * amount));
        int green = Math.round(startG + ((endG - startG) * amount));
        int blue = Math.round(startB + ((endB - startB) * amount));
        return (red << 16) | (green << 8) | blue;
    }

    public enum GuiStyle {
        MODERN("Modern"),
        CLASSIC("Classic");

        private final String label;

        GuiStyle(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
