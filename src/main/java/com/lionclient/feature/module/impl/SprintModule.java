package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

public final class SprintModule extends Module {
    public SprintModule() {
        super("Sprint", "Automatically keeps you sprinting.", Category.MOVEMENT, Keyboard.KEY_NONE);
    }

    @Override
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }

        KeyBinding.setKeyBindState(minecraft.gameSettings.keyBindSprint.getKeyCode(), true);
    }

    @Override
    protected void onDisable() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.gameSettings != null) {
            KeyBinding.setKeyBindState(minecraft.gameSettings.keyBindSprint.getKeyCode(), false);
        }
    }
}
