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
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class LegitScaffoldModule extends Module {
    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.LEGIT);
    private final NumberSetting sneakDelay = new NumberSetting("Sneak Delay", 0, 250, 5, 60);

    private long sneakReleaseTime;

    public LegitScaffoldModule() {
        super("LegitScaffold", "Sneaks at block edges.", Category.MOVEMENT, Keyboard.KEY_NONE);
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

        World world = Minecraft.getMinecraft().theWorld;
        double[] movement = getMovementOffset(player);
        AxisAlignedBB box = player.getEntityBoundingBox();
        AxisAlignedBB projectedBox = box.offset(movement[0], 0.0D, movement[1]);
        double sampleY = projectedBox.minY - 0.08D;
        double[] lateral = getLateralOffset(movement);
        double leadX = player.posX + movement[0];
        double leadZ = player.posZ + movement[1];
        double sideReach = Math.max(0.20D, (projectedBox.maxX - projectedBox.minX) * 0.48D);
        double sideX = lateral[0] * sideReach;
        double sideZ = lateral[1] * sideReach;

        boolean centerSupported = hasSupport(world, leadX, sampleY, leadZ);
        boolean leftSupported = hasSupport(world, leadX + sideX, sampleY, leadZ + sideZ);
        boolean rightSupported = hasSupport(world, leadX - sideX, sampleY, leadZ - sideZ);

        return !centerSupported || (!leftSupported && !rightSupported);
    }

    private double[] getMovementOffset(EntityPlayerSP player) {
        float forward = player.movementInput.moveForward;
        float strafe = player.movementInput.moveStrafe;
        float magnitude = MathHelper.sqrt_float(forward * forward + strafe * strafe);
        if (magnitude < 0.001F) {
            return new double[] {0.0D, 0.0D};
        }

        forward /= magnitude;
        strafe /= magnitude;

        double yawRadians = Math.toRadians(player.rotationYaw);
        double sin = Math.sin(yawRadians);
        double cos = Math.cos(yawRadians);
        double motionX = strafe * cos - forward * sin;
        double motionZ = forward * cos + strafe * sin;
        double horizontalMotion = Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
        double projection = Math.max(0.24D, Math.min(0.34D, horizontalMotion + 0.08D));
        return new double[] {motionX * projection, motionZ * projection};
    }

    private double[] getLateralOffset(double[] movement) {
        double length = Math.sqrt(movement[0] * movement[0] + movement[1] * movement[1]);
        if (length < 1.0E-4D) {
            return new double[] {1.0D, 0.0D};
        }
        return new double[] {-movement[1] / length, movement[0] / length};
    }

    private boolean hasSupport(World world, double x, double y, double z) {
        BlockPos samplePos = new BlockPos(
            MathHelper.floor_double(x),
            MathHelper.floor_double(y),
            MathHelper.floor_double(z)
        );
        return world.getBlockState(samplePos).getBlock().getMaterial() != Material.air;
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
