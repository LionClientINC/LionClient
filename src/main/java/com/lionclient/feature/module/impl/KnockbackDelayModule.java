package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.input.Keyboard;

public final class KnockbackDelayModule extends Module {
    private final NumberSetting delay = new NumberSetting("Delay", 0, 2000, 10, 200);
    private final NumberSetting triggerWindow = new NumberSetting("Trigger Window", 1, 1000, 10, 250);
    private final DecimalSetting nearbyPlayerRange = new DecimalSetting("Nearby Range", 0.5D, 12.0D, 0.5D, 5.0D);
    private final BooleanSetting renderIndicator = new BooleanSetting("Render Indicator", false);

    private volatile long delayEndAt;
    private volatile long pendingHoldEndAt;
    private volatile long suppressLocalTriggersUntil;
    private volatile boolean inboundFlushRequested;
    private volatile long pendingKnockbackAt;
    private volatile long pendingDamageAt;
    private int lastHurtTime;
    private float lastHealth = -1.0F;

    public KnockbackDelayModule() {
        super("KnockbackDelay", "Delays all packets for a set period after knockback.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(delay);
        addSetting(triggerWindow);
        addSetting(nearbyPlayerRange);
        addSetting(renderIndicator);
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        requestFlush();
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null || minecraft.thePlayer.isDead) {
            requestFlush();
            lastHurtTime = 0;
            lastHealth = -1.0F;
            return;
        }

        if (minecraft.thePlayer.hurtTime > lastHurtTime) {
            registerLocalDamageSignal();
        }
        lastHurtTime = minecraft.thePlayer.hurtTime;
        lastHealth = minecraft.thePlayer.getHealth();

        if (getRemainingDelayMillis() <= 0) {
            delayEndAt = 0L;
        }

        expirePendingSignals();
    }

    @Override
    public void onInboundPacket(Packet<?> packet) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }

        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocityPacket = (S12PacketEntityVelocity) packet;
            if (velocityPacket.getEntityID() == minecraft.thePlayer.getEntityId()) {
                registerKnockbackSignal();
            }
            return;
        }

        if (packet instanceof S27PacketExplosion) {
            registerKnockbackSignal();
            return;
        }

        if (packet instanceof S06PacketUpdateHealth) {
            S06PacketUpdateHealth healthPacket = (S06PacketUpdateHealth) packet;
            if (lastHealth >= 0.0F && healthPacket.getHealth() < lastHealth) {
                registerInboundDamageSignal();
            }
            lastHealth = healthPacket.getHealth();
        }
    }

    @Override
    public int getOutboundPacketDelay(Packet<?> packet) {
        return 0;
    }

    @Override
    public int getInboundPacketDelay(Packet<?> packet) {
        return getCurrentHoldMillis();
    }

    @Override
    public boolean isOutboundPacketDelayActive() {
        return false;
    }

    @Override
    public boolean isInboundPacketDelayActive() {
        return getCurrentHoldMillis() > 0;
    }

    @Override
    public boolean consumeOutboundFlushRequest() {
        return false;
    }

    @Override
    public boolean consumeInboundFlushRequest() {
        boolean requested = inboundFlushRequested;
        inboundFlushRequested = false;
        return requested;
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

        int remaining = getCurrentHoldMillis();
        if (remaining <= 0) {
            return;
        }

        ScaledResolution resolution = event.resolution;
        String text = "Delaying packets: " + remaining + "ms";
        int x = (resolution.getScaledWidth() - minecraft.fontRendererObj.getStringWidth(text)) / 2;
        int y = resolution.getScaledHeight() / 2 + 18;
        minecraft.fontRendererObj.drawStringWithShadow(text, x, y, 0xFFFFAA00);
    }

    private void registerKnockbackSignal() {
        long now = System.currentTimeMillis();
        if (!hasNearbyPlayerInRange()) {
            return;
        }

        pendingKnockbackAt = now;
        pendingHoldEndAt = Math.max(pendingHoldEndAt, now + getTriggerWindowMillis());
        tryArmDelay(now);
    }

    private void registerLocalDamageSignal() {
        long now = System.currentTimeMillis();
        if (now < suppressLocalTriggersUntil || !hasNearbyPlayerInRange()) {
            return;
        }

        pendingDamageAt = now;
        pendingHoldEndAt = Math.max(pendingHoldEndAt, now + getTriggerWindowMillis());
        tryArmDelay(now);
    }

    private void registerInboundDamageSignal() {
        long now = System.currentTimeMillis();
        if (!hasNearbyPlayerInRange()) {
            return;
        }

        pendingDamageAt = now;
        pendingHoldEndAt = Math.max(pendingHoldEndAt, now + getTriggerWindowMillis());
        tryArmDelay(now);
    }

    private void tryArmDelay(long now) {
        int configuredDelay = delay.getValue();
        if (configuredDelay <= 0) {
            return;
        }

        if (pendingKnockbackAt <= 0L || pendingDamageAt <= 0L) {
            return;
        }

        if (Math.abs(pendingKnockbackAt - pendingDamageAt) > getTriggerWindowMillis()) {
            return;
        }

        delayEndAt = Math.max(delayEndAt, now + configuredDelay);
        pendingHoldEndAt = 0L;
        suppressLocalTriggersUntil = Math.max(suppressLocalTriggersUntil, delayEndAt + 150L);
        pendingKnockbackAt = 0L;
        pendingDamageAt = 0L;
    }

    private void expirePendingSignals() {
        long now = System.currentTimeMillis();
        long triggerWindowMillis = getTriggerWindowMillis();
        if (pendingKnockbackAt > 0L && now - pendingKnockbackAt > triggerWindowMillis) {
            pendingKnockbackAt = 0L;
            pendingHoldEndAt = 0L;
        }
        if (pendingDamageAt > 0L && now - pendingDamageAt > triggerWindowMillis) {
            pendingDamageAt = 0L;
        }
    }

    private boolean hasNearbyPlayerInRange() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return false;
        }

        double nearbyPlayerRangeSq = getNearbyPlayerRangeSq();
        for (Object object : minecraft.theWorld.playerEntities) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }

            EntityPlayer player = (EntityPlayer) object;
            if (player == minecraft.thePlayer
                || player.isDead
                || player.getHealth() <= 0.0F
                || player.isInvisible()
                || AntiBotModule.shouldIgnore(player)) {
                continue;
            }

            if (minecraft.thePlayer.getDistanceSqToEntity(player) <= nearbyPlayerRangeSq) {
                return true;
            }
        }

        return false;
    }

    private long getTriggerWindowMillis() {
        return triggerWindow.getValue();
    }

    private double getNearbyPlayerRangeSq() {
        double range = nearbyPlayerRange.getValue();
        return range * range;
    }

    private int getRemainingDelayMillis() {
        return (int) Math.max(0L, delayEndAt - System.currentTimeMillis());
    }

    private int getCurrentHoldMillis() {
        long now = System.currentTimeMillis();
        long activeUntil = Math.max(delayEndAt, pendingHoldEndAt);
        return (int) Math.max(0L, activeUntil - now);
    }

    private void requestFlush() {
        delayEndAt = 0L;
        pendingHoldEndAt = 0L;
        suppressLocalTriggersUntil = 0L;
        pendingKnockbackAt = 0L;
        pendingDamageAt = 0L;
        inboundFlushRequested = true;
    }

    private void resetState() {
        delayEndAt = 0L;
        pendingHoldEndAt = 0L;
        suppressLocalTriggersUntil = 0L;
        pendingKnockbackAt = 0L;
        pendingDamageAt = 0L;
        inboundFlushRequested = false;
        lastHurtTime = 0;
        Minecraft minecraft = Minecraft.getMinecraft();
        lastHealth = minecraft.thePlayer == null ? -1.0F : minecraft.thePlayer.getHealth();
    }
}
