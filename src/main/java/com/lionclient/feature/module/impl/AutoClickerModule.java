package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class AutoClickerModule extends Module {
    private final Random random = new Random();
    private final Method clickMouseMethod;
    private final Method guiClickMethod;
    private final Field leftClickCounterField;

    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.NORMAL);
    private final BooleanSetting breakBlocks = new BooleanSetting("Break Blocks", true);
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", false);
    private final BooleanSetting inventoryFill = new BooleanSetting("Inventory Fill", false);
    private final NumberSetting minCps = new NumberSetting("Min CPS", 1, 25, 1, 9);
    private final NumberSetting maxCps = new NumberSetting("Max CPS", 1, 25, 1, 13);
    private final NumberSetting jitterStrength = new NumberSetting("Jitter", 0, 10, 1, 0);

    private long nextClickAt;
    private long lastInventoryClickAt;
    private int burstTicks;
    private int recordIndex;
    private boolean recordNoticeShown;
    private Mode lastMode;

    public AutoClickerModule() {
        super("AutoClicker", "Automates clicks with normal and recorded modes.", Category.COMBAT, Keyboard.KEY_R);
        clickMouseMethod = findClickMouseMethod();
        guiClickMethod = findGuiClickMethod();
        leftClickCounterField = findLeftClickCounterField();
        addSetting(mode);
        addSetting(breakBlocks);
        addSetting(weaponOnly);
        addSetting(inventoryFill);
        addSetting(minCps);
        addSetting(maxCps);
        addSetting(jitterStrength);
    }

    @Override
    protected void onEnable() {
        resetRuntimeState();
    }

    @Override
    protected void onDisable() {
        KeyBinding.setKeyBindState(Minecraft.getMinecraft().gameSettings.keyBindAttack.getKeyCode(), false);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null) {
            resetRuntimeState();
            return;
        }

        normalizeRanges();

        if (mode.getValue() != lastMode) {
            resetRuntimeState();
            lastMode = mode.getValue();
        }

        if (minecraft.currentScreen != null) {
            handleInventoryFill(minecraft);
            return;
        }

        if (!minecraft.inGameHasFocus || !Mouse.isButtonDown(0)) {
            recordNoticeShown = false;
            return;
        }

        if (weaponOnly.isEnabled() && !isHoldingWeapon(minecraft)) {
            return;
        }

        if (breakBlocks.isEnabled() && isBreakingBlock(minecraft)) {
            return;
        }

        applyJitter(minecraft);

        if (mode.getValue() == Mode.RECORD) {
            handleRecordedPattern(minecraft);
            return;
        }

        handleNormalMode(minecraft);
    }

    private void handleNormalMode(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        if (now < nextClickAt) {
            return;
        }

        performClick(minecraft);
        nextClickAt = now + computeDelayMillis();
    }

    private void handleRecordedPattern(Minecraft minecraft) {
        List<Integer> delays = ClickPatternStore.getDelays();
        if (delays.isEmpty()) {
            if (!recordNoticeShown) {
                sendChat("No recorded pattern. Use ClickRecorder in CLIENT first.");
                recordNoticeShown = true;
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextClickAt) {
            return;
        }

        performClick(minecraft);
        int delay = delays.get(recordIndex);
        recordIndex = (recordIndex + 1) % delays.size();
        nextClickAt = now + Math.max(0, delay);
        recordNoticeShown = false;
    }

    private void handleInventoryFill(Minecraft minecraft) {
        if (!inventoryFill.isEnabled()) {
            return;
        }

        if (!(minecraft.currentScreen instanceof GuiInventory) && !(minecraft.currentScreen instanceof GuiChest)) {
            return;
        }

        boolean shiftDown = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        if (!Mouse.isButtonDown(0) || !shiftDown) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < lastInventoryClickAt) {
            return;
        }

        clickInsideGui(minecraft, minecraft.currentScreen);
        lastInventoryClickAt = now + computeDelayMillis();
    }

    private void clickInsideGui(Minecraft minecraft, GuiScreen guiScreen) {
        if (guiClickMethod == null) {
            return;
        }

        int mouseX = Mouse.getX() * guiScreen.width / minecraft.displayWidth;
        int mouseY = guiScreen.height - Mouse.getY() * guiScreen.height / minecraft.displayHeight - 1;

        try {
            guiClickMethod.invoke(guiScreen, Integer.valueOf(mouseX), Integer.valueOf(mouseY), Integer.valueOf(0));
        } catch (IllegalAccessException | InvocationTargetException ignored) {
        }
    }

    private void performClick(Minecraft minecraft) {
        if (clickMouseMethod == null) {
            return;
        }

        try {
            if (leftClickCounterField != null) {
                leftClickCounterField.setInt(minecraft, 0);
            }
            clickMouseMethod.invoke(minecraft);
        } catch (IllegalAccessException | InvocationTargetException ignored) {
        }
    }

    private boolean isBreakingBlock(Minecraft minecraft) {
        MovingObjectPosition hitResult = minecraft.objectMouseOver;
        return minecraft.playerController != null
            && minecraft.playerController.getIsHittingBlock()
            && hitResult != null
            && hitResult.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
    }

    private boolean isHoldingWeapon(Minecraft minecraft) {
        if (minecraft.thePlayer.getHeldItem() == null) {
            return false;
        }

        String name = minecraft.thePlayer.getHeldItem().getUnlocalizedName();
        return name != null && (name.contains("sword") || name.contains("axe"));
    }

    private long computeDelayMillis() {
        int min = minCps.getValue();
        int max = Math.max(min, maxCps.getValue());
        double cps = min + (random.nextDouble() * (max - min + 1));
        if (burstTicks <= 0) {
            burstTicks = 4 + random.nextInt(7);
        }
        burstTicks--;
        cps += Math.sin(System.nanoTime() / 70000000.0D) * 0.8D;
        cps += random.nextGaussian() * 0.35D;
        cps += burstTicks % 3 == 0 ? -0.6D : 0.25D;
        cps = Math.max(1.0D, cps);
        return Math.max(1L, Math.round(1000.0D / cps));
    }

    private void applyJitter(Minecraft minecraft) {
        int strength = jitterStrength.getValue();
        if (strength <= 0) {
            return;
        }

        float yawDelta = (random.nextFloat() - 0.5F) * (0.3F * strength);
        float pitchDelta = (random.nextFloat() - 0.5F) * (0.18F * strength);
        minecraft.thePlayer.rotationYaw += yawDelta;
        minecraft.thePlayer.rotationPitch = clampPitch(minecraft.thePlayer.rotationPitch + pitchDelta);
    }

    private float clampPitch(float pitch) {
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    private void normalizeRanges() {
        if (maxCps.getValue() < minCps.getValue()) {
            maxCps.setValue(minCps.getValue());
        }
    }

    private void resetRuntimeState() {
        nextClickAt = 0L;
        lastInventoryClickAt = 0L;
        burstTicks = 0;
        recordIndex = 0;
        recordNoticeShown = false;
        lastMode = mode.getValue();
    }

    private void sendChat(String text) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer != null) {
            minecraft.thePlayer.addChatMessage(new ChatComponentText("[AutoClicker] " + text));
        }
    }

    private Method findClickMouseMethod() {
        try {
            Method method = Minecraft.class.getDeclaredMethod("clickMouse");
            method.setAccessible(true);
            return method;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Method findGuiClickMethod() {
        try {
            Method method = GuiScreen.class.getDeclaredMethod("mouseClicked", Integer.TYPE, Integer.TYPE, Integer.TYPE);
            method.setAccessible(true);
            return method;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Field findLeftClickCounterField() {
        try {
            Field field = Minecraft.class.getDeclaredField("leftClickCounter");
            field.setAccessible(true);
            return field;
        } catch (Exception ignored) {
            return null;
        }
    }

    private enum Mode {
        NORMAL,
        RECORD
    }
}
