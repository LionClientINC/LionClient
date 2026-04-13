package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.MouseEvent;
import org.lwjgl.input.Keyboard;

public final class ClickRecorderModule extends Module {
    private final BooleanSetting showMessage = new BooleanSetting("Show Message", true);
    private long lastClickAt;

    public ClickRecorderModule() {
        super("ClickRecorder", "Records left-click timings for AutoClicker record mode.", Category.CLIENT, Keyboard.KEY_NONE);
        addSetting(showMessage);
    }

    @Override
    public boolean showsKeybindSetting() {
        return false;
    }

    @Override
    protected void onEnable() {
        ClickPatternStore.clear();
        lastClickAt = 0L;
        sendChat("Started recording clicks.");
    }

    @Override
    protected void onDisable() {
        if (ClickPatternStore.isEmpty()) {
            sendChat("Recording stopped with no captured clicks.");
            return;
        }

        sendChat("Saved " + ClickPatternStore.size() + " recorded clicks.");
    }

    @Override
    public void onMouseEvent(MouseEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.currentScreen != null) {
            return;
        }

        if (event.button != 0 || !event.buttonstate) {
            return;
        }

        long now = System.nanoTime();
        int delay = lastClickAt == 0L ? 0 : (int) ((now - lastClickAt) / 1000000L);
        lastClickAt = now;
        ClickPatternStore.addDelay(delay);

        if (showMessage.isEnabled()) {
            sendChat("Captured click " + ClickPatternStore.size() + " (" + delay + "ms)");
        }
    }

    private void sendChat(String text) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer != null) {
            minecraft.thePlayer.addChatMessage(new ChatComponentText("[ClickRecorder] " + text));
        }
    }
}
