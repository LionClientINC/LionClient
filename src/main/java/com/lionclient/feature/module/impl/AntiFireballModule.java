package com.lionclient.feature.module.impl;

import com.lionclient.combat.ClientRotationHelper;
import com.lionclient.combat.KillAuraRotationUtils;
import com.lionclient.event.ClientRotationEvent;
import com.lionclient.event.PrePlayerInputEvent;
import com.lionclient.event.PrePlayerInteractEvent;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.NumberSetting;
import com.lionclient.util.MouseButtonHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;

public final class AntiFireballModule extends Module {
    private final NumberSetting fov = new NumberSetting("FOV", 30, 360, 4, 360);
    private final DecimalSetting range = new DecimalSetting("Range", 3.0D, 15.0D, 0.5D, 8.0D);
    private final DecimalSetting targetCps = new DecimalSetting("Target CPS", 1.0D, 20.0D, 0.5D, 12.0D);
    private final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 1, 30, 1, 15);
    private final BooleanSetting onGround = new BooleanSetting("On Ground", false);
    private final BooleanSetting sneakWhileActive = new BooleanSetting("Sneak While Active", false);

    private final Set<Entity> trackedFireballs = new HashSet<Entity>();
    private final Random random = new Random();
    private final java.lang.reflect.Field pointedEntityField;

    private EntityFireball fireball;
    private long nextClickTime;
    private boolean forgeRegistered;

    public AntiFireballModule() {
        super("AntiFireball", "Automatically aims at and hits nearby fireballs.", Category.PLAYER, Keyboard.KEY_NONE);
        addSetting(fov);
        addSetting(range);
        addSetting(targetCps);
        addSetting(rotationSpeed);
        addSetting(onGround);
        addSetting(sneakWhileActive);
        pointedEntityField = findRendererField("field_78528_u", "pointedEntity");
    }

    @Override
    protected void onEnable() {
        nextClickTime = 0L;
        fireball = null;
        trackedFireballs.clear();
        registerForge();
        seedTrackedFireballs();
    }

    @Override
    protected void onDisable() {
        unregisterForge();
        nextClickTime = 0L;
        fireball = null;
        trackedFireballs.clear();
        ClientRotationHelper.get().clearRequestedRotations();
    }

    @Override
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (!canProcess(minecraft)) {
            fireball = null;
            nextClickTime = 0L;
            return;
        }

        fireball = findFireball(minecraft);
        if (shouldCancelMovement(minecraft)) {
            minecraft.thePlayer.setSprinting(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!shouldAim(minecraft)) {
            return;
        }

        float baseYaw = event.yaw != null ? event.yaw.floatValue() : resolveBaseYaw(minecraft);
        float basePitch = event.pitch != null ? event.pitch.floatValue() : resolveBasePitch(minecraft);
        float[] targetRotations = computeAimRotations(minecraft, baseYaw, basePitch);
        if (targetRotations == null) {
            return;
        }

        float[] smooth = KillAuraRotationUtils.smoothRotation(
            baseYaw,
            basePitch,
            targetRotations[0],
            targetRotations[1],
            rotationSpeed.getValue(),
            0.0F
        );
        event.yaw = Float.valueOf(smooth[0]);
        event.pitch = Float.valueOf(smooth[1]);
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!shouldCancelMovement(minecraft)) {
            return;
        }

        event.setForward(0.0F);
        event.setStrafe(0.0F);
        event.setJump(false);
        if (sneakWhileActive.isEnabled() && !minecraft.thePlayer.isRiding() && !minecraft.thePlayer.capabilities.isFlying) {
            event.setSneak(true);
        }
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!shouldAim(minecraft)) {
            nextClickTime = 0L;
            return;
        }

        MovingObjectPosition mouseOver = minecraft.objectMouseOver;
        if (mouseOver == null
            || mouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY
            || mouseOver.entityHit != fireball) {
            nextClickTime = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (nextClickTime == 0L) {
            nextClickTime = now;
        }

        int key = minecraft.gameSettings.keyBindAttack.getKeyCode();
        while (nextClickTime <= now) {
            KeyBinding.onTick(key);
            MouseButtonHelper.setButton(0, true);
            nextClickTime += nextDelay();
        }
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null) {
            return;
        }

        if (event.entity == minecraft.thePlayer) {
            trackedFireballs.clear();
            return;
        }

        if (event.entity instanceof EntityFireball && minecraft.thePlayer.getDistanceSqToEntity(event.entity) > 16.0D) {
            trackedFireballs.add(event.entity);
        }
    }

    public void modifyMouseOverFromGetMouseOver(float partialTicks) {
        if (!shouldAim(Minecraft.getMinecraft())) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        Entity viewEntity = minecraft.getRenderViewEntity();
        if (viewEntity == null) {
            return;
        }

        Vec3 eyes = viewEntity.getPositionEyes(partialTicks);
        Vec3 look = viewEntity.getLook(partialTicks);
        double reach = minecraft.playerController == null ? 3.0D : minecraft.playerController.getBlockReachDistance();
        Vec3 rayEnd = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);

        float border = fireball.getCollisionBorderSize();
        AxisAlignedBB box = fireball.getEntityBoundingBox().expand(border, border, border);
        MovingObjectPosition intercept = box.calculateIntercept(eyes, rayEnd);
        boolean inside = box.isVecInside(eyes);
        if (!inside && intercept == null) {
            return;
        }

        Vec3 hitVec = inside ? (intercept == null ? eyes : intercept.hitVec) : intercept.hitVec;
        MovingObjectPosition blockHit = minecraft.theWorld.rayTraceBlocks(eyes, hitVec, false, false, true);
        if (blockHit != null && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }

        minecraft.objectMouseOver = new MovingObjectPosition(fireball, hitVec);
        minecraft.pointedEntity = fireball;
        if (pointedEntityField != null) {
            try {
                pointedEntityField.set(minecraft.entityRenderer, fireball);
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    private boolean canProcess(Minecraft minecraft) {
        return minecraft != null
            && minecraft.thePlayer != null
            && minecraft.theWorld != null
            && !minecraft.thePlayer.isDead
            && minecraft.currentScreen == null;
    }

    private boolean shouldAim(Minecraft minecraft) {
        if (!canProcess(minecraft) || fireball == null || fireball.isDead) {
            return false;
        }
        return !onGround.isEnabled() || minecraft.thePlayer.onGround;
    }

    private boolean shouldCancelMovement(Minecraft minecraft) {
        return shouldAim(minecraft);
    }

    private void seedTrackedFireballs() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }

        for (Object object : minecraft.theWorld.loadedEntityList) {
            if (!(object instanceof EntityFireball)) {
                continue;
            }

            Entity entity = (Entity) object;
            if (minecraft.thePlayer.getDistanceSqToEntity(entity) > 16.0D) {
                trackedFireballs.add(entity);
            }
        }
    }

    private EntityFireball findFireball(Minecraft minecraft) {
        double rangeSq = range.getValue() * range.getValue();
        float fovValue = (float) fov.getValue();
        EntityFireball best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Object object : minecraft.theWorld.loadedEntityList) {
            if (!(object instanceof EntityFireball)) {
                continue;
            }

            EntityFireball candidate = (EntityFireball) object;
            if (candidate.isDead) {
                trackedFireballs.remove(candidate);
                continue;
            }
            if (!trackedFireballs.contains(candidate)) {
                continue;
            }

            double distanceSq = minecraft.thePlayer.getDistanceSqToEntity(candidate);
            if (distanceSq > rangeSq) {
                continue;
            }
            if (fovValue != 360.0F && !isInFov(minecraft, candidate, fovValue)) {
                continue;
            }

            if (distanceSq < bestDistance) {
                bestDistance = distanceSq;
                best = candidate;
            }
        }

        return best;
    }

    private boolean isInFov(Minecraft minecraft, Entity entity, float fovValue) {
        Vec3 eye = minecraft.thePlayer.getPositionEyes(1.0F);
        Vec3 point = KillAuraRotationUtils.getAimPoint(entity, 100.0D, 100.0D);
        if (point == null) {
            AxisAlignedBB box = entity.getEntityBoundingBox();
            point = new Vec3(
                (box.minX + box.maxX) * 0.5D,
                (box.minY + box.maxY) * 0.5D,
                (box.minZ + box.maxZ) * 0.5D
            );
        }

        double deltaX = point.xCoord - eye.xCoord;
        double deltaZ = point.zCoord - eye.zCoord;
        float targetYaw = (float) (Math.atan2(deltaZ, deltaX) * 57.295780181884766D) - 90.0F;
        float yawDifference = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - minecraft.thePlayer.rotationYaw));
        return yawDifference <= fovValue * 0.5F;
    }

    private float[] computeAimRotations(Minecraft minecraft, float baseYaw, float basePitch) {
        if (fireball == null) {
            return null;
        }

        Vec3 eye = minecraft.thePlayer.getPositionEyes(1.0F);
        float border = fireball.getCollisionBorderSize();
        AxisAlignedBB fireballBox = fireball.getEntityBoundingBox().expand(border, border, border);
        double reach = minecraft.playerController == null ? 3.0D : minecraft.playerController.getBlockReachDistance();

        List<EntityPlayer> players = new ArrayList<EntityPlayer>();
        for (EntityPlayer player : minecraft.theWorld.playerEntities) {
            if (player == minecraft.thePlayer || player.deathTime != 0 || player.getHealth() <= 0.0F) {
                continue;
            }
            if (AntiBotModule.shouldIgnore(player)) {
                continue;
            }
            players.add(player);
        }

        Collections.sort(players, Comparator.comparingDouble(new java.util.function.ToDoubleFunction<EntityPlayer>() {
            @Override
            public double applyAsDouble(EntityPlayer value) {
                return minecraft.thePlayer.getDistanceSqToEntity(value);
            }
        }));

        for (EntityPlayer player : players) {
            float[] rotations = KillAuraRotationUtils.getRotationsToPoint(player.posX, player.posY, player.posZ, baseYaw, basePitch);
            if (rotations != null && hitsFireballBox(eye, rotations[0], rotations[1], fireballBox, reach)) {
                return rotations;
            }
        }

        double topY = fireballBox.maxY;
        double centerX = (fireballBox.minX + fireballBox.maxX) * 0.5D;
        double centerZ = (fireballBox.minZ + fireballBox.maxZ) * 0.5D;
        return KillAuraRotationUtils.getRotationsToPoint(centerX, topY, centerZ, baseYaw, basePitch);
    }

    private boolean hitsFireballBox(Vec3 eye, float yaw, float pitch, AxisAlignedBB box, double range) {
        Vec3 look = KillAuraRotationUtils.getVectorForRotation(pitch, yaw);
        Vec3 end = eye.addVector(look.xCoord * range, look.yCoord * range, look.zCoord * range);
        return box.calculateIntercept(eye, end) != null;
    }

    private long nextDelay() {
        int cps = Math.max(1, (int) targetCps.getValue());
        int baseDelay = 1000 / cps;
        int variation = random.nextInt(Math.max(1, baseDelay / 3 + 1)) - baseDelay / 6;
        return Math.max(33, baseDelay + variation);
    }

    private float resolveBaseYaw(Minecraft minecraft) {
        return Float.isNaN(KillAuraRotationUtils.serverRotations[0]) ? minecraft.thePlayer.rotationYaw : KillAuraRotationUtils.serverRotations[0];
    }

    private float resolveBasePitch(Minecraft minecraft) {
        return Float.isNaN(KillAuraRotationUtils.serverRotations[1]) ? minecraft.thePlayer.rotationPitch : KillAuraRotationUtils.serverRotations[1];
    }

    private void registerForge() {
        if (forgeRegistered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(this);
        forgeRegistered = true;
    }

    private void unregisterForge() {
        if (!forgeRegistered) {
            return;
        }
        MinecraftForge.EVENT_BUS.unregister(this);
        forgeRegistered = false;
    }

    private static java.lang.reflect.Field findRendererField(String... names) {
        try {
            java.lang.reflect.Field field = ReflectionHelper.findField(EntityRenderer.class, names);
            field.setAccessible(true);
            return field;
        } catch (Exception ignored) {
            return null;
        }
    }
}
