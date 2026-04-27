package com.lionclient.feature.module.impl;

import com.lionclient.LionClient;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.input.Keyboard;

public final class KnockbackDelayModule extends Module {
    private final NumberSetting minDelay = new NumberSetting("Min Delay", 0, 5000, 10, 1900);
    private final NumberSetting maxDelay = new NumberSetting("Max Delay", 0, 5000, 10, 2100);
    private final NumberSetting chance = new NumberSetting("Chance", 0, 100, 1, 100);
    private final BooleanSetting renderIndicator = new BooleanSetting("Render Indicator", false);

    private final Random random = new Random();

    private volatile long holdPacketsUntil;
    private volatile int cachedPlayerId;

    public KnockbackDelayModule() {
        super("KnockbackDelay", "Buffers all incoming packets when your velocity packet is received.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(minDelay);
        addSetting(maxDelay);
        addSetting(chance);
        addSetting(renderIndicator);
    }

    @Override
    protected void onEnable() {
        resetState(false);
    }

    @Override
    protected void onDisable() {
        holdPacketsUntil = 0L;
        cachedPlayerId = 0;

        LionClient client = LionClient.getInstance();
        if (client != null) {
            client.getKnockbackDelayBuffer().flushAllIncoming();
        }
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null || minecraft.thePlayer.isDead) {
            resetState(true);
            return;
        }

        cachedPlayerId = minecraft.thePlayer.getEntityId();
        if (getRemainingHoldMillis() <= 0) {
            holdPacketsUntil = 0L;
        }
    }

    @Override
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (!renderIndicator.isEnabled()) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.fontRendererObj == null || minecraft.gameSettings.showDebugInfo) {
            return;
        }

        int remaining = getRemainingHoldMillis();
        if (remaining <= 0) {
            return;
        }

        ScaledResolution resolution = event.resolution;
        String text = "Delaying packets: " + remaining + "ms";
        int x = (resolution.getScaledWidth() - minecraft.fontRendererObj.getStringWidth(text)) / 2;
        int y = resolution.getScaledHeight() / 2 + 18;
        minecraft.fontRendererObj.drawStringWithShadow(text, x, y, 0xFFFF5050);
    }

    public void tryTriggerVelocity(S12PacketEntityVelocity velocityPacket) {
        if (isHolding() || cachedPlayerId == 0 || velocityPacket.getEntityID() != cachedPlayerId) {
            return;
        }

        int configuredChance = chance.getValue();
        if (configuredChance < 100 && random.nextInt(100) >= configuredChance) {
            return;
        }

        int low = Math.min(minDelay.getValue(), maxDelay.getValue());
        int high = Math.max(minDelay.getValue(), maxDelay.getValue());
        int holdMillis = high > low ? low + random.nextInt(high - low + 1) : low;
        if (holdMillis <= 0) {
            return;
        }

        holdPacketsUntil = System.currentTimeMillis() + holdMillis;
    }

    public boolean isHolding() {
        return getRemainingHoldMillis() > 0;
    }

    public int getRemainingHoldMillis() {
        return (int) Math.max(0L, holdPacketsUntil - System.currentTimeMillis());
    }

    private void resetState(boolean unused) {
        holdPacketsUntil = 0L;
        cachedPlayerId = 0;
    }
}
