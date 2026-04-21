package com.lionclient.combat;

import com.lionclient.event.ClientRotationEvent;
import com.lionclient.event.JumpEvent;
import com.lionclient.event.StrafeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class ClientRotationHelper {
    private static final ClientRotationHelper INSTANCE = new ClientRotationHelper();

    private final Minecraft minecraft = Minecraft.getMinecraft();

    private Float serverYaw;
    private Float serverPitch;
    private boolean setRotations;
    private boolean rotationsUpdatedThisTick;
    private float savedYaw;
    private float savedPitch;
    private float savedPrevYaw;
    private float savedPrevPitch;

    public boolean swappedForMouseOver;
    private boolean swappedForWalkingUpdate;

    private ClientRotationHelper() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static ClientRotationHelper get() {
        return INSTANCE;
    }

    public static float unwrapYaw(float yaw, float previousYaw) {
        return previousYaw + ((((yaw - previousYaw + 180.0F) % 360.0F) + 360.0F) % 360.0F - 180.0F);
    }

    public void onRunTickStart() {
        if (minecraft.thePlayer != null && setRotations && serverYaw != null && !serverYaw.isNaN()) {
            float serverYawValue = Float.isNaN(KillAuraRotationUtils.serverRotations[0]) ? serverYaw.floatValue() : KillAuraRotationUtils.serverRotations[0];
            float unwrappedYaw = unwrapYaw(MathHelper.wrapAngleTo180_float(minecraft.thePlayer.rotationYaw), serverYawValue);
            minecraft.thePlayer.rotationYaw = unwrappedYaw;
            minecraft.thePlayer.prevRotationYaw = unwrappedYaw;
        }

        if (minecraft.thePlayer != null && Float.isNaN(KillAuraRotationUtils.serverRotations[0])) {
            KillAuraRotationUtils.serverRotations[0] = minecraft.thePlayer.rotationYaw;
            KillAuraRotationUtils.serverRotations[1] = minecraft.thePlayer.rotationPitch;
        }

        serverYaw = null;
        serverPitch = null;
        setRotations = false;
        rotationsUpdatedThisTick = false;
        swappedForMouseOver = false;
        swappedForWalkingUpdate = false;
    }

    public void clearRequestedRotations() {
        serverYaw = null;
        serverPitch = null;
        setRotations = false;
    }

    public void updateServerRotations() {
        if (minecraft.thePlayer == null || rotationsUpdatedThisTick) {
            return;
        }

        rotationsUpdatedThisTick = true;
        if (Float.isNaN(KillAuraRotationUtils.serverRotations[0])) {
            KillAuraRotationUtils.serverRotations[0] = minecraft.thePlayer.rotationYaw;
            KillAuraRotationUtils.serverRotations[1] = minecraft.thePlayer.rotationPitch;
        }

        ClientRotationEvent event = new ClientRotationEvent(serverYaw, serverPitch);
        MinecraftForge.EVENT_BUS.post(event);
        serverYaw = event.yaw;
        serverPitch = event.pitch;
        if (serverYaw == null && serverPitch == null) {
            return;
        }

        float baseYaw = Float.isNaN(KillAuraRotationUtils.serverRotations[0]) ? minecraft.thePlayer.rotationYaw : KillAuraRotationUtils.serverRotations[0];
        float basePitch = Float.isNaN(KillAuraRotationUtils.serverRotations[1]) ? minecraft.thePlayer.rotationPitch : KillAuraRotationUtils.serverRotations[1];
        float[] fixed = KillAuraRotationUtils.fixRotation(
            serverYaw == null ? minecraft.thePlayer.rotationYaw : serverYaw.floatValue(),
            serverPitch == null ? minecraft.thePlayer.rotationPitch : serverPitch.floatValue(),
            baseYaw,
            basePitch
        );
        if (serverYaw != null) {
            serverYaw = Float.valueOf(fixed[0]);
        }
        if (serverPitch != null) {
            serverPitch = Float.valueOf(fixed[1]);
        }
        if ((serverYaw != null && !serverYaw.isNaN() && serverYaw.floatValue() != minecraft.thePlayer.rotationYaw)
            || (serverPitch != null && !serverPitch.isNaN() && serverPitch.floatValue() != minecraft.thePlayer.rotationPitch)) {
            setRotations = true;
        }
    }

    public void onWalkingUpdatePre(Entity entity) {
        if (entity == null || minecraft.thePlayer == null || entity != minecraft.thePlayer) {
            return;
        }

        if (setRotations) {
            float yaw = serverYaw != null && !serverYaw.isNaN() ? serverYaw.floatValue() : entity.rotationYaw;
            float pitch = serverPitch != null && !serverPitch.isNaN() ? serverPitch.floatValue() : entity.rotationPitch;
            beginSwap(entity, yaw, pitch, true);
            swappedForWalkingUpdate = true;
            KillAuraRotationUtils.serverRotations[0] = yaw;
            KillAuraRotationUtils.serverRotations[1] = pitch;
            return;
        }

        KillAuraRotationUtils.serverRotations[0] = entity.rotationYaw;
        KillAuraRotationUtils.serverRotations[1] = entity.rotationPitch;
    }

    public boolean isActive() {
        return setRotations && (serverYaw != null || serverPitch != null);
    }

    public Float getServerYaw() {
        return serverYaw;
    }

    public Float getServerPitch() {
        return serverPitch;
    }

    public void beginSwap(Entity entity, float yaw, float pitch, boolean swapPitch) {
        savedYaw = entity.rotationYaw;
        savedPrevYaw = entity.prevRotationYaw;
        savedPitch = entity.rotationPitch;
        savedPrevPitch = entity.prevRotationPitch;

        entity.rotationYaw = yaw;
        entity.prevRotationYaw = yaw;
        if (swapPitch) {
            entity.rotationPitch = pitch;
            entity.prevRotationPitch = pitch;
        }
    }

    public void endSwap(Entity entity) {
        entity.rotationYaw = savedYaw;
        entity.prevRotationYaw = savedPrevYaw;
        entity.rotationPitch = savedPitch;
        entity.prevRotationPitch = savedPrevPitch;
    }

    public void onWalkingUpdatePost(Entity entity) {
        if (!swappedForWalkingUpdate || entity == null) {
            return;
        }

        endSwap(entity);
        swappedForWalkingUpdate = false;
    }

    public void fixMovementInputs() {
        if (minecraft.thePlayer == null || minecraft.thePlayer.movementInput == null || !canFixMovement()) {
            return;
        }

        float forward = minecraft.thePlayer.movementInput.moveForward;
        float strafe = minecraft.thePlayer.movementInput.moveStrafe;
        if (forward == 0.0F && strafe == 0.0F) {
            return;
        }

        float sneakMultiplier = minecraft.thePlayer.movementInput.sneak ? 0.3F : 1.0F;
        double angle = MathHelper.wrapAngleTo180_double(Math.toDegrees(getDirection(minecraft.thePlayer.rotationYaw, forward, strafe)));
        float closestForward = 0.0F;
        float closestStrafe = 0.0F;
        float closestDifference = Float.MAX_VALUE;

        for (float predictedForwardRaw = -1.0F; predictedForwardRaw <= 1.0F; predictedForwardRaw += 1.0F) {
            for (float predictedStrafeRaw = -1.0F; predictedStrafeRaw <= 1.0F; predictedStrafeRaw += 1.0F) {
                if (predictedForwardRaw == 0.0F && predictedStrafeRaw == 0.0F) {
                    continue;
                }

                float predictedForward = predictedForwardRaw * sneakMultiplier;
                float predictedStrafe = predictedStrafeRaw * sneakMultiplier;
                double predictedAngle = MathHelper.wrapAngleTo180_double(Math.toDegrees(getDirection(serverYaw.floatValue(), predictedForward, predictedStrafe)));
                double difference = Math.abs(angle - predictedAngle);
                if (difference < closestDifference) {
                    closestDifference = (float) difference;
                    closestForward = predictedForward;
                    closestStrafe = predictedStrafe;
                }
            }
        }

        minecraft.thePlayer.movementInput.moveForward = closestForward;
        minecraft.thePlayer.movementInput.moveStrafe = closestStrafe;
    }

    @SubscribeEvent
    public void onStrafe(StrafeEvent event) {
        if (canFixMovement()) {
            event.setYaw(serverYaw.floatValue());
        }
    }

    @SubscribeEvent
    public void onJump(JumpEvent event) {
        if (canFixMovement()) {
            event.setYaw(serverYaw.floatValue());
        }
    }

    private boolean canFixMovement() {
        return setRotations && serverYaw != null && !serverYaw.isNaN();
    }

    private static double getDirection(float rotationYaw, double moveForward, double moveStrafing) {
        if (moveForward < 0.0D) {
            rotationYaw += 180.0F;
        }

        float forward = 1.0F;
        if (moveForward < 0.0D) {
            forward = -0.5F;
        } else if (moveForward > 0.0D) {
            forward = 0.5F;
        }

        if (moveStrafing > 0.0D) {
            rotationYaw -= 90.0F * forward;
        }
        if (moveStrafing < 0.0D) {
            rotationYaw += 90.0F * forward;
        }

        return Math.toRadians(rotationYaw);
    }
}
