package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

public final class SprintModule extends Module {
    public SprintModule() {
        super("Sprint", "Automatically keeps you sprinting.", Category.MOVEMENT, Keyboard.KEY_NONE);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.thePlayer;
        if (player == null) {
            return;
        }

        int sprintKey = minecraft.gameSettings.keyBindSprint.getKeyCode();
        boolean movingForward = player.movementInput != null && player.movementInput.moveForward > 0.0F;
        boolean shouldHoldSprint = minecraft.currentScreen == null
            && movingForward
            && !player.isSneaking()
            && player.getFoodStats().getFoodLevel() > 6;

        KeyBinding.setKeyBindState(sprintKey, shouldHoldSprint);
    }

    @Override
    protected void onDisable() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.gameSettings != null) {
            KeyBinding.setKeyBindState(minecraft.gameSettings.keyBindSprint.getKeyCode(), false);
        }
    }
}
