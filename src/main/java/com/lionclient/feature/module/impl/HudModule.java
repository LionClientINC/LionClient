package com.lionclient.feature.module.impl;

import com.lionclient.LionClient;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.NumberSetting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.input.Keyboard;

public final class HudModule extends Module {
    private final NumberSetting red = new NumberSetting("Red", 0, 255, 5, 255);
    private final NumberSetting green = new NumberSetting("Green", 0, 255, 5, 255);
    private final NumberSetting blue = new NumberSetting("Blue", 0, 255, 5, 255);

    public HudModule() {
        super("HUD", "Displays enabled modules on screen.", Category.RENDER, Keyboard.KEY_NONE);
        addSetting(red);
        addSetting(green);
        addSetting(blue);
    }

    @Override
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.gameSettings.showDebugInfo) {
            return;
        }

        List<Module> enabled = new ArrayList<Module>();
        for (Module module : LionClient.getInstance().getModuleManager().getModules()) {
            if (!module.isEnabled() || module == this) {
                continue;
            }
            enabled.add(module);
        }

        Collections.sort(enabled, new Comparator<Module>() {
            @Override
            public int compare(Module left, Module right) {
                int leftWidth = minecraft.fontRendererObj.getStringWidth(left.getName());
                int rightWidth = minecraft.fontRendererObj.getStringWidth(right.getName());
                return rightWidth - leftWidth;
            }
        });

        int color = 0xFF000000 | ((red.getValue() & 255) << 16) | ((green.getValue() & 255) << 8) | (blue.getValue() & 255);
        int y = 4;
        for (Module module : enabled) {
            String name = module.getName();
            int x = event.resolution.getScaledWidth() - minecraft.fontRendererObj.getStringWidth(name) - 4;
            minecraft.fontRendererObj.drawStringWithShadow(name, x, y, color);
            y += minecraft.fontRendererObj.FONT_HEIGHT + 2;
        }
    }
}
