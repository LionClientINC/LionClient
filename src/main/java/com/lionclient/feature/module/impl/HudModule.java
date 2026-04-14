package com.lionclient.feature.module.impl;

import com.lionclient.LionClient;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.ActionSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.input.Keyboard;

public final class HudModule extends Module {
    private static final int DEFAULT_X = 4;
    private static final int DEFAULT_Y = 4;
    private static HudModule instance;

    private final NumberSetting red = new NumberSetting("Red", 0, 255, 5, 255);
    private final NumberSetting green = new NumberSetting("Green", 0, 255, 5, 255);
    private final NumberSetting blue = new NumberSetting("Blue", 0, 255, 5, 255);
    private final NumberSetting x = new NumberSetting("X", 0, 4000, 2, DEFAULT_X);
    private final NumberSetting y = new NumberSetting("Y", 0, 4000, 2, DEFAULT_Y);
    private final ActionSetting editor = new ActionSetting("Move HUD", new Runnable() {
        @Override
        public void run() {
            LionClient client = LionClient.getInstance();
            if (client != null) {
                client.openHudEditor();
            }
        }
    }, new ActionSetting.ValueProvider() {
        @Override
        public String get() {
            return "OPEN";
        }
    });

    public HudModule() {
        super("HUD", "Displays enabled modules on screen.", Category.RENDER, Keyboard.KEY_NONE);
        instance = this;
        addSetting(red);
        addSetting(green);
        addSetting(blue);
        addSetting(x);
        addSetting(y);
        addSetting(editor);
    }

    @Override
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.gameSettings.showDebugInfo) {
            return;
        }

        renderModuleList(event.resolution, getEnabledModuleNames(), getColor());
    }

    public void renderEditorPreview(ScaledResolution resolution) {
        List<String> previewLines = getEnabledModuleNames();
        if (previewLines.isEmpty()) {
            previewLines.add("KillAura");
            previewLines.add("AutoClicker");
            previewLines.add("Sprint");
        }
        renderModuleList(resolution, previewLines, getColor());
    }

    public static HudModule getInstance() {
        return instance;
    }

    public int getAnchorX() {
        return x.getValue();
    }

    public int getAnchorY() {
        return y.getValue();
    }

    public void setPosition(int x, int y) {
        this.x.setValue(x);
        this.y.setValue(y);
    }

    public boolean isRightAligned(ScaledResolution resolution) {
        return x.getValue() >= resolution.getScaledWidth() / 2;
    }

    public int getPreviewWidth(Minecraft minecraft) {
        return getMaxTextWidth(minecraft, getPreviewModuleNames());
    }

    public int getPreviewHeight(Minecraft minecraft) {
        return getPreviewModuleNames().size() * (minecraft.fontRendererObj.FONT_HEIGHT + 2);
    }

    private void renderModuleList(ScaledResolution resolution, List<String> moduleNames, int color) {
        Minecraft minecraft = Minecraft.getMinecraft();
        int anchorX = Math.max(0, Math.min(x.getValue(), resolution.getScaledWidth()));
        int anchorY = Math.max(0, Math.min(y.getValue(), Math.max(0, resolution.getScaledHeight() - minecraft.fontRendererObj.FONT_HEIGHT)));
        boolean rightAligned = anchorX >= resolution.getScaledWidth() / 2;
        int lineY = anchorY;

        for (String moduleName : moduleNames) {
            int drawX = rightAligned ? anchorX - minecraft.fontRendererObj.getStringWidth(moduleName) : anchorX;
            minecraft.fontRendererObj.drawStringWithShadow(moduleName, drawX, lineY, color);
            lineY += minecraft.fontRendererObj.FONT_HEIGHT + 2;
        }
    }

    private List<String> getEnabledModuleNames() {
        List<String> moduleNames = new ArrayList<String>();
        LionClient client = LionClient.getInstance();
        if (client == null) {
            return moduleNames;
        }

        for (Module module : client.getModuleManager().getModules()) {
            if (!module.isEnabled() || module == this) {
                continue;
            }
            moduleNames.add(module.getName());
        }

        sortByWidth(moduleNames);
        return moduleNames;
    }

    private List<String> getPreviewModuleNames() {
        List<String> moduleNames = getEnabledModuleNames();
        if (moduleNames.isEmpty()) {
            moduleNames.add("KillAura");
            moduleNames.add("AutoClicker");
            moduleNames.add("Sprint");
            sortByWidth(moduleNames);
        }
        return moduleNames;
    }

    private void sortByWidth(final List<String> moduleNames) {
        final Minecraft minecraft = Minecraft.getMinecraft();
        Collections.sort(moduleNames, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return minecraft.fontRendererObj.getStringWidth(right) - minecraft.fontRendererObj.getStringWidth(left);
            }
        });
    }

    private int getMaxTextWidth(Minecraft minecraft, List<String> moduleNames) {
        int width = 0;
        for (String moduleName : moduleNames) {
            width = Math.max(width, minecraft.fontRendererObj.getStringWidth(moduleName));
        }
        return width;
    }

    private int getColor() {
        return 0xFF000000 | ((red.getValue() & 255) << 16) | ((green.getValue() & 255) << 8) | (blue.getValue() & 255);
    }
}
