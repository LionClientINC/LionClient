package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class KnockbackDelayModule extends Module {
    private static final double MELEE_RANGE = 4.25D;

    private final DecimalSetting distanceToTarget = new DecimalSetting("Distance to target", 3.0D, 12.0D, 0.1D, 6.0D);
    private final NumberSetting chance = new NumberSetting("Chance", 0, 100, 1, 100);
    private final NumberSetting maximumDelay = new NumberSetting("Maximum delay", 50, 1000, 10, 200);
    private final BooleanSetting inAir = new BooleanSetting("In Air", false);
    private final BooleanSetting lookingAtPlayer = new BooleanSetting("Looking at player", false);
    private final BooleanSetting requireLeftMouse = new BooleanSetting("Require Left mouse", false);
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", false);
    private final BooleanSetting renderIndicator = new BooleanSetting("Render Delay", true);

    private long inboundDelayEndAt;
    private long statusMessageUntil;
    private boolean inboundFlushRequested;
    private int lastHurtTime;
    private String statusMessage = "";

    public KnockbackDelayModule() {
        super("Knockback Delay", "Delays inbound knockback packets after velocity, Raven-style.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(distanceToTarget);
        addSetting(chance);
        addSetting(maximumDelay);
        addSetting(inAir);
        addSetting(lookingAtPlayer);
        addSetting(requireLeftMouse);
        addSetting(weaponOnly);
        addSetting(renderIndicator);
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
            lastHurtTime = 0;
            return;
        }

        if (minecraft.thePlayer.hurtTime > lastHurtTime) {
            tryArmFromDamage(minecraft);
        }
        lastHurtTime = minecraft.thePlayer.hurtTime;

        String conditionFailure = getConditionFailure(minecraft);
        if (inboundDelayEndAt > 0L && conditionFailure != null) {
            showStatus("Flushed: " + conditionFailure);
            requestInboundFlush();
            return;
        }

        if (getRemainingDelayMillis() > 0) {
            suppressKnockbackMotion(minecraft);
        }
    }

    @Override
    public void onInboundPacket(Packet<?> packet) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }

        if (packet instanceof S08PacketPlayerPosLook) {
            showStatus("Flush: position correction");
            requestInboundFlush();
            return;
        }

        if (!(packet instanceof S12PacketEntityVelocity) && !(packet instanceof S27PacketExplosion)) {
            return;
        }

        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocityPacket = (S12PacketEntityVelocity) packet;
            if (velocityPacket.getEntityID() != minecraft.thePlayer.getEntityId()) {
                return;
            }
        }

        String conditionFailure = getConditionFailure(minecraft);
        if (conditionFailure != null) {
            showStatus("Blocked: " + conditionFailure);
            return;
        }

        if (chance.getValue() < 100 && Math.random() * 100.0D >= chance.getValue()) {
            showStatus("Blocked: chance");
            return;
        }

        int delay = maximumDelay.getValue();
        inboundDelayEndAt = System.currentTimeMillis() + delay;
        showStatus("Delaying " + delay + "ms");
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

        if (inboundDelayEndAt > now && (packet instanceof S12PacketEntityVelocity || packet instanceof S27PacketExplosion)) {
            if (getConditionFailure(minecraft) != null) {
                requestInboundFlush();
                return 0;
            }
            return (int) Math.max(1L, inboundDelayEndAt - now);
        }

        return 0;
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

    @Override
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (!renderIndicator.isEnabled()) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.gameSettings.showDebugInfo || minecraft.fontRendererObj == null) {
            return;
        }

        int remaining = getRemainingDelayMillis();
        ScaledResolution resolution = event.resolution;
        if (remaining > 0) {
            String text = "KB Delay: " + remaining + "ms";
            int x = (resolution.getScaledWidth() - minecraft.fontRendererObj.getStringWidth(text)) / 2;
            int y = resolution.getScaledHeight() / 2 + 18;
            minecraft.fontRendererObj.drawStringWithShadow(text, x, y, 0xFFFFAA00);
        }

        if (statusMessage.isEmpty() || System.currentTimeMillis() > statusMessageUntil) {
            return;
        }

        int statusX = (resolution.getScaledWidth() - minecraft.fontRendererObj.getStringWidth(statusMessage)) / 2;
        int statusY = resolution.getScaledHeight() / 2 + 30;
        minecraft.fontRendererObj.drawStringWithShadow(statusMessage, statusX, statusY, 0xFFFFFFFF);
    }

    private void resetState() {
        inboundDelayEndAt = 0L;
        inboundFlushRequested = false;
        lastHurtTime = 0;
        statusMessageUntil = 0L;
        statusMessage = "";
    }

    private void requestInboundFlush() {
        inboundDelayEndAt = 0L;
        inboundFlushRequested = true;
    }

    private int getRemainingDelayMillis() {
        return (int) Math.max(0L, inboundDelayEndAt - System.currentTimeMillis());
    }

    private void tryArmFromDamage(Minecraft minecraft) {
        String conditionFailure = getConditionFailure(minecraft);
        if (conditionFailure != null) {
            showStatus("Blocked: " + conditionFailure);
            return;
        }

        if (chance.getValue() < 100 && Math.random() * 100.0D >= chance.getValue()) {
            showStatus("Blocked: chance");
            return;
        }

        int delay = maximumDelay.getValue();
        inboundDelayEndAt = System.currentTimeMillis() + delay;
        showStatus("Delaying " + delay + "ms");
    }

    private void suppressKnockbackMotion(Minecraft minecraft) {
        if (minecraft.thePlayer == null) {
            return;
        }

        minecraft.thePlayer.motionX = 0.0D;
        minecraft.thePlayer.motionZ = 0.0D;
        if (minecraft.thePlayer.motionY > 0.0D) {
            minecraft.thePlayer.motionY = 0.0D;
        }
    }

    private String getConditionFailure(Minecraft minecraft) {
        if (inAir.isEnabled() && minecraft.thePlayer.onGround) {
            return "not in air";
        }

        EntityPlayer target = findClosestTarget(minecraft, distanceToTarget.getValue());
        EntityPlayer meleeTarget = findClosestTarget(minecraft, MELEE_RANGE);
        if (meleeTarget == null) {
            return "no melee target";
        }

        if (!isValidMeleeAttacker(meleeTarget)) {
            return "target using rod/bow";
        }

        if (lookingAtPlayer.isEnabled()) {
            if (target == null) {
                return "no target";
            }
            if (!isLookingAtTarget(minecraft, target, distanceToTarget.getValue())) {
                return "not looking at player";
            }
        }

        if (requireLeftMouse.isEnabled() && !Mouse.isButtonDown(0)) {
            return "left mouse not held";
        }

        if (weaponOnly.isEnabled() && !isHoldingWeapon(minecraft.thePlayer.getHeldItem())) {
            return "not holding weapon";
        }

        return null;
    }

    private void showStatus(String text) {
        statusMessage = text == null ? "" : text;
        statusMessageUntil = System.currentTimeMillis() + 1500L;
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

    private boolean isValidMeleeAttacker(EntityPlayer player) {
        if (player == null) {
            return false;
        }

        ItemStack heldItem = player.getHeldItem();
        if (heldItem == null) {
            return true;
        }

        String name = heldItem.getUnlocalizedName();
        if (name == null) {
            return true;
        }

        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        if (lowerName.contains("bow") || lowerName.contains("rod") || lowerName.contains("fishing")) {
            return false;
        }

        return true;
    }
}
