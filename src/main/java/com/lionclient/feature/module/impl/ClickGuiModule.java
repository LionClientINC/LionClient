package com.lionclient.feature.module.impl;

import com.lionclient.LionClient;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import org.lwjgl.input.Keyboard;

public final class ClickGuiModule extends Module {
    private static final int DEFAULT_CLASSIC_ACCENT_COLOR = 0x305CA8;
    private static final int DEFAULT_MODERN_ACCENT_COLOR = 0x4A9EFF;
    private static ClickGuiModule instance;

    private final EnumSetting<GuiStyle> style = new EnumSetting<GuiStyle>("Style", GuiStyle.values(), GuiStyle.MODERN);
    private final NumberSetting red = new NumberSetting("Red", 0, 255, 5, 48);
    private final NumberSetting green = new NumberSetting("Green", 0, 255, 5, 92);
    private final NumberSetting blue = new NumberSetting("Blue", 0, 255, 5, 168);
    private final BooleanSetting snowflakes = new BooleanSetting("Snowflakes", true);
    private final NumberSetting modernRed = new NumberSetting("Modern Red", 0, 255, 1, 74);
    private final NumberSetting modernGreen = new NumberSetting("Modern Green", 0, 255, 1, 158);
    private final NumberSetting modernBlue = new NumberSetting("Modern Blue", 0, 255, 1, 255);

    public ClickGuiModule() {
        super("ClickGUI", "Configure the ClickGUI", Category.CLIENT, Keyboard.KEY_RSHIFT);
        instance = this;
        addSetting(style);
        addSetting(snowflakes);
        addSetting(modernRed);
        addSetting(modernGreen);
        addSetting(modernBlue);
        addSetting(red);
        addSetting(green);
        addSetting(blue);

        java.util.function.BooleanSupplier classicVisibility = new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return style.getValue() == GuiStyle.CLASSIC;
            }
        };
        java.util.function.BooleanSupplier modernVisibility = new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return style.getValue() == GuiStyle.MODERN;
            }
        };

        snowflakes.setVisibility(modernVisibility);
        modernRed.setVisibility(modernVisibility);
        modernGreen.setVisibility(modernVisibility);
        modernBlue.setVisibility(modernVisibility);
        red.setVisibility(classicVisibility);
        green.setVisibility(classicVisibility);
        blue.setVisibility(classicVisibility);
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
            return DEFAULT_CLASSIC_ACCENT_COLOR;
        }
        return toColor(instance.red, instance.green, instance.blue);
    }

    public static GuiStyle getGuiStyle() {
        return instance == null ? GuiStyle.MODERN : instance.style.getValue();
    }

    public static ClickGuiModule getInstance() {
        return instance;
    }

    public static int getModernAccentColor() {
        if (instance == null) {
            return DEFAULT_MODERN_ACCENT_COLOR;
        }
        return toColor(instance.modernRed, instance.modernGreen, instance.modernBlue);
    }

    public static boolean areSnowflakesEnabled() {
        return instance == null || instance.snowflakes.isEnabled();
    }

    public static int getLightAccentColor() {
        return blendColor(getModernAccentColor(), 0xFFFFFF, 0.52F);
    }

    public static int getDarkAccentColor() {
        return blendColor(getModernAccentColor(), 0x08111B, 0.48F);
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

    private static int toColor(NumberSetting red, NumberSetting green, NumberSetting blue) {
        return ((red.getValue() & 255) << 16)
            | ((green.getValue() & 255) << 8)
            | (blue.getValue() & 255);
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
