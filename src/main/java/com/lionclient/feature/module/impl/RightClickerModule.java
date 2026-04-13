package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class RightClickerModule extends Module {
    private final Random random = new Random();

    private final BooleanSetting onlyBlocks = new BooleanSetting("Only Blocks", false);
    private final NumberSetting minCps = new NumberSetting("Min CPS", 1, 25, 1, 9);
    private final NumberSetting maxCps = new NumberSetting("Max CPS", 1, 25, 1, 13);
    private final NumberSetting jitterStrength = new NumberSetting("Jitter", 0, 10, 1, 0);

    private long lastClick;
    private long holdUntil;
    private int burstTicks;
    private boolean rightDown;

    public RightClickerModule() {
        super("RightClicker", "Automatically right-clicks for you.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(onlyBlocks);
        addSetting(minCps);
        addSetting(maxCps);
        addSetting(jitterStrength);
    }

    @Override
    protected void onEnable() {
        resetClickState();
    }

    @Override
    protected void onDisable() {
        resetClickState();
    }

    @Override
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }

        normalizeRanges();
        if (minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            stopAutoClicking();
            return;
        }

        Mouse.poll();
        if (!Mouse.isButtonDown(1)) {
            resetPhysicalState();
            return;
        }

        if (onlyBlocks.isEnabled() && !isHoldingBlock(minecraft)) {
            stopAutoClicking();
            return;
        }

        applyJitter(minecraft);
        normalClick();
    }

    private void normalClick() {
        long delay = computeDelayMillis();
        long holdLength = Math.max(1L, delay / 2L);
        long now = System.currentTimeMillis();

        if (now - lastClick >= delay) {
            lastClick = now;
            holdUntil = now + holdLength;
            sendClick(true);
            rightDown = true;
        } else if (rightDown && now >= holdUntil) {
            sendClick(false);
            rightDown = false;
        }
    }

    private void sendClick(boolean pressed) {
        Minecraft minecraft = Minecraft.getMinecraft();
        int key = minecraft.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(key, pressed);
        if (pressed) {
            KeyBinding.onTick(key);
        }
    }

    private void applyJitter(Minecraft minecraft) {
        int strength = jitterStrength.getValue();
        if (strength <= 0) {
            return;
        }

        float yawDelta = (random.nextBoolean() ? 1 : -1) * random.nextFloat() * (strength * 0.45F);
        float pitchDelta = (random.nextBoolean() ? 1 : -1) * random.nextFloat() * (strength * 0.2F);
        minecraft.thePlayer.rotationYaw += yawDelta;
        minecraft.thePlayer.rotationPitch = clampPitch(minecraft.thePlayer.rotationPitch + pitchDelta);
    }

    private long computeDelayMillis() {
        int min = minCps.getValue();
        int max = Math.max(min, maxCps.getValue());
        double cps = min + (random.nextDouble() * (max - min + 1));
        if (burstTicks <= 0) {
            burstTicks = 3 + random.nextInt(9);
        }
        burstTicks--;
        cps += Math.sin(System.nanoTime() / 65000000.0D) * 0.95D;
        cps += random.nextGaussian() * 0.55D;
        cps += burstTicks % 4 == 0 ? -0.85D : 0.35D;
        if (random.nextDouble() < 0.08D) {
            cps -= 0.6D + (random.nextDouble() * 0.9D);
        }
        if (random.nextDouble() < 0.05D) {
            cps += 0.4D + (random.nextDouble() * 0.8D);
        }
        cps = Math.max(1.0D, cps);
        return Math.max(1L, Math.round(1000.0D / cps));
    }

    private void resetClickState() {
        lastClick = 0L;
        holdUntil = 0L;
        resetPhysicalState();
    }

    private void stopAutoClicking() {
        rightDown = false;
        syncUseItemKeyWithPhysicalMouse();
    }

    private void resetPhysicalState() {
        rightDown = false;
        sendClick(false);
    }

    private void syncUseItemKeyWithPhysicalMouse() {
        Minecraft minecraft = Minecraft.getMinecraft();
        int key = minecraft.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(key, Mouse.isButtonDown(1));
    }

    private void normalizeRanges() {
        if (maxCps.getValue() < minCps.getValue()) {
            maxCps.setValue(minCps.getValue());
        }
    }

    private boolean isHoldingBlock(Minecraft minecraft) {
        return minecraft.thePlayer.getHeldItem() != null
            && minecraft.thePlayer.getHeldItem().getItem() instanceof ItemBlock;
    }

    private float clampPitch(float pitch) {
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }
}
