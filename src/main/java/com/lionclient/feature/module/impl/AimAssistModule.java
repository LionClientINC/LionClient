package com.lionclient.feature.module.impl;

import com.lionclient.combat.KillAuraRotationUtils;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemSword;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class AimAssistModule extends Module {
    private final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 1, 20, 1, 4);
    private final NumberSetting randomization = new NumberSetting("Randomization", 0, 30, 1, 0);
    private final NumberSetting fov = new NumberSetting("FOV", 15, 360, 1, 90);
    private final DecimalSetting distance = new DecimalSetting("Distance", 1.0D, 10.0D, 0.5D, 4.5D);
    private final BooleanSetting clickAim = new BooleanSetting("Click Aim", true);
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", false);
    private final BooleanSetting targetInvis = new BooleanSetting("Target Invis", false);
    private final BooleanSetting breakBlocks = new BooleanSetting("Break Blocks", true);

    private long lastRenderUpdateNanos = -1L;

    public AimAssistModule() {
        super("AimAssist", "Smoothly nudges your aim toward nearby players.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(rotationSpeed);
        addSetting(randomization);
        addSetting(fov);
        addSetting(distance);
        addSetting(clickAim);
        addSetting(weaponOnly);
        addSetting(targetInvis);
        addSetting(breakBlocks);
    }

    @Override
    protected void onEnable() {
        lastRenderUpdateNanos = -1L;
    }

    @Override
    protected void onDisable() {
        lastRenderUpdateNanos = -1L;
    }

    @Override
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (!shouldAssist(minecraft)) {
            lastRenderUpdateNanos = -1L;
            return;
        }

        float currentYaw = minecraft.thePlayer.rotationYaw;
        float currentPitch = minecraft.thePlayer.rotationPitch;
        AimTarget target = findTarget(minecraft, currentYaw, currentPitch);
        if (target == null) {
            return;
        }

        float frameFactor = consumeFrameFactor();
        float[] smoothed = applyLinearSmoothing(currentYaw, currentPitch, target.yaw, target.pitch, frameFactor);
        applyClientRotations(minecraft, smoothed[0], smoothed[1]);
    }

    private AimTarget findTarget(Minecraft minecraft, float baseYaw, float basePitch) {
        double maxDistance = distance.getValue();
        double bestAngleScore = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        AimTarget bestTarget = null;

        for (EntityPlayer candidate : minecraft.theWorld.playerEntities) {
            if (!isValidTarget(minecraft, candidate, maxDistance)) {
                continue;
            }

            double candidateDistance = KillAuraRotationUtils.distanceFromEyeToClosestOnAABB(candidate);
            float[] rotations = KillAuraRotationUtils.getRotationsWithBackup(
                candidate,
                100.0D,
                100.0D,
                baseYaw,
                basePitch,
                maxDistance,
                false,
                false
            );
            if (rotations == null) {
                continue;
            }

            float yawDifference = Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - baseYaw));
            if (yawDifference > fov.getValue() * 0.5F) {
                continue;
            }

            float pitchDifference = Math.abs(rotations[1] - basePitch);
            double angleScore = yawDifference * yawDifference + pitchDifference * pitchDifference * 0.35D;
            if (angleScore < bestAngleScore || (angleScore == bestAngleScore && candidateDistance < bestDistance)) {
                bestAngleScore = angleScore;
                bestDistance = candidateDistance;
                bestTarget = new AimTarget(rotations[0], rotations[1]);
            }
        }

        return bestTarget;
    }

    private boolean isValidTarget(Minecraft minecraft, EntityPlayer candidate, double maxDistance) {
        if (candidate == null || candidate == minecraft.thePlayer || candidate.isDead) {
            return false;
        }
        if (candidate.deathTime != 0 || candidate.getHealth() <= 0.0F || AntiBotModule.shouldIgnore(candidate)) {
            return false;
        }
        if (candidate.isInvisible() && !targetInvis.isEnabled()) {
            return false;
        }

        return KillAuraRotationUtils.distanceFromEyeToClosestOnAABB(candidate) <= maxDistance;
    }

    private boolean canAim(Minecraft minecraft) {
        return minecraft != null
            && minecraft.thePlayer != null
            && minecraft.theWorld != null
            && !minecraft.thePlayer.isDead
            && minecraft.currentScreen == null
            && minecraft.inGameHasFocus
            && (!weaponOnly.isEnabled() || isHoldingWeapon(minecraft));
    }

    private boolean shouldAssist(Minecraft minecraft) {
        return canAim(minecraft)
            && (!breakBlocks.isEnabled() || !isBreakingBlock(minecraft))
            && (!clickAim.isEnabled() || Mouse.isButtonDown(0));
    }

    private boolean isBreakingBlock(Minecraft minecraft) {
        MovingObjectPosition mouseOver = minecraft.objectMouseOver;
        return mouseOver != null && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
    }

    private boolean isHoldingWeapon(Minecraft minecraft) {
        if (minecraft.thePlayer.getHeldItem() == null) {
            return false;
        }

        Item item = minecraft.thePlayer.getHeldItem().getItem();
        if (item instanceof ItemSword || item == Items.stick) {
            return true;
        }

        String name = item.getUnlocalizedName();
        return name != null && name.contains("axe");
    }

    private void applyClientRotations(Minecraft minecraft, float yaw, float pitch) {
        minecraft.thePlayer.prevRotationYaw = minecraft.thePlayer.rotationYaw;
        minecraft.thePlayer.prevRotationPitch = minecraft.thePlayer.rotationPitch;
        minecraft.thePlayer.prevRotationYawHead = minecraft.thePlayer.rotationYawHead;
        minecraft.thePlayer.prevRenderYawOffset = minecraft.thePlayer.renderYawOffset;
        minecraft.thePlayer.rotationYaw = yaw;
        minecraft.thePlayer.rotationPitch = pitch;
        minecraft.thePlayer.rotationYawHead = yaw;
        minecraft.thePlayer.renderYawOffset = yaw;
    }

    private float[] applyLinearSmoothing(float currentYaw, float currentPitch, float targetYaw, float targetPitch, float frameFactor) {
        float yawDelta = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDelta = targetPitch - currentPitch;
        float magnitude = MathHelper.sqrt_float(yawDelta * yawDelta + pitchDelta * pitchDelta);
        if (magnitude < 0.001F) {
            return new float[]{targetYaw, KillAuraRotationUtils.clampPitch(targetPitch)};
        }

        float baseFollowStrength = 0.025F + rotationSpeed.getValue() * 0.009F;
        float followStrength = 1.0F - (float) Math.pow(1.0F - Math.min(0.95F, baseFollowStrength), frameFactor);
        float minStep = 0.015F * frameFactor;
        float maxStep = (0.12F + rotationSpeed.getValue() * 0.12F) * frameFactor;
        float variance = randomization.getValue() / 100.0F;
        float stepMultiplier = 1.0F;
        if (variance > 0.0F) {
            float spread = variance * 0.08F * frameFactor;
            stepMultiplier = 1.0F - spread * 0.5F + (float) (Math.random() * spread);
        }

        float desiredStep = Math.max(minStep, magnitude * followStrength) * stepMultiplier;
        float step = Math.min(magnitude, Math.min(maxStep, desiredStep));
        float scale = step / magnitude;
        return new float[]{
            currentYaw + yawDelta * scale,
            KillAuraRotationUtils.clampPitch(currentPitch + pitchDelta * scale)
        };
    }

    private float consumeFrameFactor() {
        long now = System.nanoTime();
        if (lastRenderUpdateNanos <= 0L) {
            lastRenderUpdateNanos = now;
            return 1.0F;
        }

        double deltaSeconds = (now - lastRenderUpdateNanos) / 1_000_000_000.0D;
        lastRenderUpdateNanos = now;
        return (float) MathHelper.clamp_double(deltaSeconds * 60.0D, 0.25D, 2.0D);
    }

    private static final class AimTarget {
        private final float yaw;
        private final float pitch;

        private AimTarget(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
