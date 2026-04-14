package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class KnockbackDelayModule extends Module {
    private final DecimalSetting distanceToTarget = new DecimalSetting("Distance to target", 3.0D, 12.0D, 0.1D, 6.0D);
    private final NumberSetting chance = new NumberSetting("Chance", 0, 100, 1, 100);
    private final NumberSetting maximumDelay = new NumberSetting("Maximum delay", 50, 1000, 10, 200);
    private final BooleanSetting inAir = new BooleanSetting("In Air", true);
    private final BooleanSetting lookingAtPlayer = new BooleanSetting("Looking at player", false);
    private final BooleanSetting requireLeftMouse = new BooleanSetting("Require Left mouse", false);
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", false);

    private long inboundDelayEndAt;
    private boolean inboundFlushRequested;

    public KnockbackDelayModule() {
        super("Knockback Delay", "Delays inbound knockback packets after velocity, Raven-style.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(distanceToTarget);
        addSetting(chance);
        addSetting(maximumDelay);
        addSetting(inAir);
        addSetting(lookingAtPlayer);
        addSetting(requireLeftMouse);
        addSetting(weaponOnly);
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        requestInboundFlush();
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null || minecraft.thePlayer.isDead) {
            requestInboundFlush();
            return;
        }

        if (inboundDelayEndAt > 0L && hasConditionFailure(minecraft)) {
            requestInboundFlush();
        }
    }

    @Override
    public int getInboundPacketDelay(Packet<?> packet) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            requestInboundFlush();
            return 0;
        }

        long now = System.currentTimeMillis();
        if (packet instanceof S08PacketPlayerPosLook) {
            requestInboundFlush();
            return 0;
        }

        if (inboundDelayEndAt > now) {
            if (hasConditionFailure(minecraft)) {
                requestInboundFlush();
                return 0;
            }
            return (int) Math.max(1L, inboundDelayEndAt - now);
        }

        if (!(packet instanceof S12PacketEntityVelocity)) {
            return 0;
        }

        S12PacketEntityVelocity velocityPacket = (S12PacketEntityVelocity) packet;
        if (velocityPacket.getEntityID() != minecraft.thePlayer.getEntityId()) {
            return 0;
        }

        if (hasConditionFailure(minecraft)) {
            return 0;
        }

        if (chance.getValue() < 100 && Math.random() * 100.0D >= chance.getValue()) {
            return 0;
        }

        int delay = maximumDelay.getValue();
        inboundDelayEndAt = now + delay;
        return delay;
    }

    @Override
    public boolean isInboundPacketDelayActive() {
        return inboundDelayEndAt > System.currentTimeMillis();
    }

    @Override
    public boolean consumeInboundFlushRequest() {
        boolean requested = inboundFlushRequested;
        inboundFlushRequested = false;
        return requested;
    }

    private void resetState() {
        inboundDelayEndAt = 0L;
        inboundFlushRequested = false;
    }

    private void requestInboundFlush() {
        inboundDelayEndAt = 0L;
        inboundFlushRequested = true;
    }

    private boolean hasConditionFailure(Minecraft minecraft) {
        EntityPlayer target = findClosestTarget(minecraft, distanceToTarget.getValue());
        if (target == null) {
            return true;
        }

        if (inAir.isEnabled() && minecraft.thePlayer.onGround) {
            return true;
        }

        if (lookingAtPlayer.isEnabled() && !isLookingAtTarget(minecraft, target, distanceToTarget.getValue())) {
            return true;
        }

        if (requireLeftMouse.isEnabled() && !Mouse.isButtonDown(0)) {
            return true;
        }

        if (weaponOnly.isEnabled() && !isHoldingWeapon(minecraft.thePlayer.getHeldItem())) {
            return true;
        }

        return false;
    }

    private EntityPlayer findClosestTarget(Minecraft minecraft, double maxDistance) {
        List<?> players = minecraft.theWorld.playerEntities;
        EntityPlayer bestTarget = null;
        double bestDistance = maxDistance;

        for (Object object : players) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }

            EntityPlayer player = (EntityPlayer) object;
            if (!isValidTarget(minecraft.thePlayer, player)) {
                continue;
            }

            double distance = minecraft.thePlayer.getDistanceToEntity(player);
            if (distance > bestDistance) {
                continue;
            }

            bestTarget = player;
            bestDistance = distance;
        }

        return bestTarget;
    }

    private boolean isLookingAtTarget(Minecraft minecraft, EntityPlayer target, double maxDistance) {
        MovingObjectPosition hitResult = minecraft.objectMouseOver;
        if (hitResult == null || !(hitResult.entityHit instanceof EntityPlayer)) {
            return false;
        }

        Entity entity = hitResult.entityHit;
        return entity == target && minecraft.thePlayer.getDistanceToEntity(entity) <= maxDistance;
    }

    private boolean isValidTarget(EntityPlayer self, EntityPlayer player) {
        return player != null
            && player != self
            && !player.isDead
            && player.getHealth() > 0.0F
            && !player.isInvisible();
    }

    private boolean isHoldingWeapon(ItemStack heldItem) {
        if (heldItem == null) {
            return false;
        }

        String name = heldItem.getUnlocalizedName();
        return name != null && (name.contains("sword") || name.contains("axe"));
    }
}
