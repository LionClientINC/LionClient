package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class LegitScaffoldModule extends Module {
    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.LEGIT);
    private final NumberSetting sneakDelay = new NumberSetting("Sneak Delay", 0, 250, 5, 60);

    private long sneakReleaseTime;

    public LegitScaffoldModule() {
        super("LegitScaffold", "Sneaks at block edges while backward bridging.", Category.MOVEMENT, Keyboard.KEY_NONE);
        addSetting(mode);
        addSetting(sneakDelay);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.thePlayer;
        if (player == null || minecraft.theWorld == null || minecraft.gameSettings == null) {
            return;
        }

        int sneakKey = minecraft.gameSettings.keyBindSneak.getKeyCode();
        if (minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            releaseSneak(sneakKey);
            return;
        }

        boolean shouldSneakAtEdge = mode.getValue() == Mode.LEGIT && shouldSneakAtEdge(player);
        if (shouldSneakAtEdge) {
            KeyBinding.setKeyBindState(sneakKey, true);
            if (shouldExtendSneakDelay(minecraft)) {
                sneakReleaseTime = System.currentTimeMillis() + sneakDelay.getValue();
            }
            return;
        }

        if (System.currentTimeMillis() < sneakReleaseTime) {
            KeyBinding.setKeyBindState(sneakKey, true);
            return;
        }

        releaseSneak(sneakKey);
    }

    @Override
    protected void onDisable() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.gameSettings != null) {
            releaseSneak(minecraft.gameSettings.keyBindSneak.getKeyCode());
        }
    }

    private boolean shouldSneakAtEdge(EntityPlayerSP player) {
        if (!player.onGround || player.isCollidedHorizontally || player.movementInput == null) {
            return false;
        }

        if (player.movementInput.moveForward >= 0.0F) {
            return false;
        }

        double yawRadians = Math.toRadians(player.rotationYaw);
        double backwardX = Math.sin(yawRadians) * 0.38D;
        double backwardZ = -Math.cos(yawRadians) * 0.38D;

        double sampleX = player.posX + backwardX;
        double sampleY = player.getEntityBoundingBox().minY - 0.08D;
        double sampleZ = player.posZ + backwardZ;

        BlockPos samplePos = new BlockPos(
            MathHelper.floor_double(sampleX),
            MathHelper.floor_double(sampleY),
            MathHelper.floor_double(sampleZ)
        );
        return Minecraft.getMinecraft().theWorld.getBlockState(samplePos).getBlock().getMaterial() == Material.air;
    }

    private boolean shouldExtendSneakDelay(Minecraft minecraft) {
        if (!Mouse.isButtonDown(1) || minecraft.objectMouseOver == null) {
            return false;
        }

        ItemStack heldItem = minecraft.thePlayer.getHeldItem();
        return heldItem != null && heldItem.getItem() instanceof ItemBlock;
    }

    private void releaseSneak(int sneakKey) {
        sneakReleaseTime = 0L;
        KeyBinding.setKeyBindState(sneakKey, false);
    }

    private enum Mode {
        LEGIT;

        @Override
        public String toString() {
            return "Legit";
        }
    }
}
