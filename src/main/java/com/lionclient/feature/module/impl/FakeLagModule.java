package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import org.lwjgl.input.Keyboard;

public final class FakeLagModule extends Module {
    private final DecimalSetting range = new DecimalSetting("Range", 1.0D, 8.0D, 0.1D, 4.0D);
    private final NumberSetting minDelay = new NumberSetting("MinDelay", 0, 1000, 10, 250);
    private final NumberSetting maxDelay = new NumberSetting("MaxDelay", 0, 1000, 10, 500);
    private final NumberSetting recoilTime = new NumberSetting("Recoil", 0, 1000, 10, 250);
    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.DYNAMIC);

    private long pauseQueueUntil;
    private boolean flushRequested;
    private int currentDelay;
    private float lastHealth = -1.0F;

    public FakeLagModule() {
        super("FakeLag [INVDEV]", "Delays outbound packets to simulate lag.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(range);
        addSetting(minDelay);
        addSetting(maxDelay);
        addSetting(recoilTime);
        addSetting(mode);
    }

    @Override
    protected void onEnable() {
        pauseQueueUntil = 0L;
        flushRequested = false;
        currentDelay = 0;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer != null) {
            lastHealth = minecraft.thePlayer.getHealth();
        }
    }

    @Override
    protected void onDisable() {
        pauseQueueUntil = 0L;
        flushRequested = true;
        currentDelay = 0;
        lastHealth = -1.0F;
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null) {
            lastHealth = -1.0F;
            pauseQueueUntil = 0L;
            currentDelay = 0;
            return;
        }

        lastHealth = minecraft.thePlayer.getHealth();
        currentDelay = canQueuePackets(minecraft) ? resolveDelay(minecraft) : 0;
    }

    @Override
    public int getOutboundPacketDelay(Packet<?> packet) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!canQueuePackets(minecraft)) {
            return 0;
        }

        if (shouldFlushOnSend(packet)) {
            pauseQueue();
            return 0;
        }

        return currentDelay;
    }

    @Override
    public void onInboundPacket(Packet<?> packet) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null) {
            return;
        }

        if (packet instanceof S08PacketPlayerPosLook || packet instanceof S27PacketExplosion) {
            pauseQueue();
            return;
        }

        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
            if (velocity.getEntityID() == minecraft.thePlayer.getEntityId()) {
                pauseQueue();
            }
            return;
        }

        if (packet instanceof S06PacketUpdateHealth) {
            S06PacketUpdateHealth healthPacket = (S06PacketUpdateHealth) packet;
            if (lastHealth >= 0.0F && healthPacket.getHealth() < lastHealth) {
                pauseQueue();
            }
            lastHealth = healthPacket.getHealth();
        }
    }

    @Override
    public boolean isPacketDelayActive() {
        return currentDelay > 0;
    }

    @Override
    public boolean consumeFlushRequest() {
        boolean requested = flushRequested;
        flushRequested = false;
        return requested;
    }

    private int resolveDelay(Minecraft minecraft) {
        int low = Math.min(minDelay.getValue(), maxDelay.getValue());
        int high = Math.max(minDelay.getValue(), maxDelay.getValue());

        if (mode.getValue() == Mode.CONSTANT) {
            return high;
        }

        EntityPlayer closest = findClosestEnemy(minecraft);
        if (closest == null) {
            return 0;
        }

        double distance = minecraft.thePlayer.getDistanceToEntity(closest);
        double maxRange = Math.max(0.1D, range.getValue());
        double normalized = 1.0D - Math.min(distance, maxRange) / maxRange;
        return low + (int) Math.round((high - low) * normalized);
    }

    private EntityPlayer findClosestEnemy(Minecraft minecraft) {
        List<?> entities = minecraft.theWorld.playerEntities;
        EntityPlayer closest = null;
        double bestDistance = range.getValue();

        for (Object object : entities) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }

            Entity entity = (Entity) object;
            if (entity == minecraft.thePlayer || entity.isDead || entity.isInvisible()) {
                continue;
            }

            double distance = minecraft.thePlayer.getDistanceToEntity(entity);
            if (distance > bestDistance) {
                continue;
            }

            closest = (EntityPlayer) entity;
            bestDistance = distance;
        }

        return closest;
    }

    private boolean shouldFlushOnSend(Packet<?> packet) {
        return packet instanceof C02PacketUseEntity
            || packet instanceof C0APacketAnimation
            || packet instanceof C08PacketPlayerBlockPlacement
            || packet instanceof C07PacketPlayerDigging;
    }

    private void pauseQueue() {
        pauseQueueUntil = System.currentTimeMillis() + recoilTime.getValue();
        currentDelay = 0;
        flushRequested = true;
    }

    private boolean canQueuePackets(Minecraft minecraft) {
        return minecraft.thePlayer != null
            && minecraft.theWorld != null
            && minecraft.currentScreen == null
            && !minecraft.thePlayer.isDead
            && !minecraft.thePlayer.isInWater()
            && System.currentTimeMillis() >= pauseQueueUntil;
    }

    private enum Mode {
        CONSTANT,
        DYNAMIC
    }
}
