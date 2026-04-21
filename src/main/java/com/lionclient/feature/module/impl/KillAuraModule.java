package com.lionclient.feature.module.impl;

import com.lionclient.combat.ClientRotationHelper;
import com.lionclient.combat.KillAuraRotationUtils;
import com.lionclient.event.ClientRotationEvent;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemSword;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class KillAuraModule extends Module {
    private static final int SILENT_ROTATION_SPEED = 10;

    private final DecimalSetting targetCps = new DecimalSetting("Target CPS", 1.0D, 20.0D, 0.5D, 17.0D);
    private final DecimalSetting attackRange = new DecimalSetting("Range (Attack)", 3.0D, 6.0D, 0.05D, 3.0D);
    private final DecimalSetting swingRange = new DecimalSetting("Range (Swing)", 3.0D, 8.0D, 0.05D, 4.5D);
    private final DecimalSetting aimRange = new DecimalSetting("Range (Aim)", 3.0D, 8.0D, 0.05D, 4.5D);
    private final NumberSetting switchDelay = new NumberSetting("Switch Delay", 50, 1000, 25, 50);
    private final NumberSetting targets = new NumberSetting("Targets", 1, 10, 1, 3);
    private final BooleanSetting targetInvis = new BooleanSetting("Target Invis", true);
    private final BooleanSetting hitThroughEntities = new BooleanSetting("Hit Through Entities", false);
    private final BooleanSetting disableInInventory = new BooleanSetting("Disable In Inventory", true);
    private final BooleanSetting disableWhileMining = new BooleanSetting("Disable While Mining", true);
    private final BooleanSetting notUsingItem = new BooleanSetting("Not Using Item", false);
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", false);

    private final Map<Integer, Integer> hitMap = new HashMap<Integer, Integer>();
    private final Random random = new Random();
    private final java.lang.reflect.Field pointedEntityField;

    private EntityLivingBase target;
    private EntityLivingBase attackingEntity;
    private double targetDistance = Double.MAX_VALUE;
    private long nextClickTime;
    private boolean forgeRegistered;

    public KillAuraModule() {
        super("KillAura", "Automatically attacks enemies.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(targetCps);
        addSetting(attackRange);
        addSetting(swingRange);
        addSetting(aimRange);
        addSetting(switchDelay);
        addSetting(targets);
        addSetting(targetInvis);
        addSetting(hitThroughEntities);
        addSetting(disableInInventory);
        addSetting(disableWhileMining);
        addSetting(notUsingItem);
        addSetting(weaponOnly);
        pointedEntityField = findRendererField("field_78528_u", "pointedEntity");
    }

    @Override
    protected void onEnable() {
        hitMap.clear();
        clearTargetState();
        registerForge();
    }

    @Override
    protected void onDisable() {
        unregisterForge();
        hitMap.clear();
        clearTargetState();
        ClientRotationHelper.get().clearRequestedRotations();
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!basicCondition(minecraft) || !settingCondition(minecraft)) {
            clearTargetState();
            return;
        }

        handleTarget(minecraft);
        if (target == null) {
            attackingEntity = null;
            return;
        }

        targetDistance = KillAuraRotationUtils.distanceFromEyeToClosestOnAABB(target);
        attackingEntity = targetDistance <= attackRange.getValue() ? target : null;

        double aimRangeValue = aimRange.getValue();
        if (targetDistance > aimRangeValue) {
            return;
        }

        float baseYaw = event.yaw != null ? event.yaw.floatValue() : resolveBaseYaw(minecraft);
        float basePitch = event.pitch != null ? event.pitch.floatValue() : resolveBasePitch(minecraft);
        float[] rotations = KillAuraRotationUtils.getRotationsWithBackup(
            target,
            100.0D,
            100.0D,
            baseYaw,
            basePitch,
            aimRangeValue,
            false,
            hitThroughEntities.isEnabled()
        );
        if (rotations == null) {
            return;
        }

        float[] smooth = KillAuraRotationUtils.smoothRotation(baseYaw, basePitch, rotations[0], rotations[1], SILENT_ROTATION_SPEED, 0.0F);
        event.yaw = Float.valueOf(smooth[0]);
        event.pitch = Float.valueOf(smooth[1]);
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }
        if (target == null || targetDistance > swingRange.getValue()) {
            return;
        }

        int key = minecraft.gameSettings.keyBindAttack.getKeyCode();
        long now = System.currentTimeMillis();
        if (nextClickTime == 0L) {
            nextClickTime = now;
        }

        int clicks = 0;
        while (nextClickTime <= now) {
            clicks++;
            nextClickTime += nextDelay();
        }

        if (!basicCondition(minecraft) || !settingCondition(minecraft)) {
            return;
        }
        if (notUsingItem.isEnabled() && minecraft.thePlayer.isUsingItem()) {
            return;
        }

        for (int i = 0; i < clicks; i++) {
            KeyBinding.onTick(key);
            MouseButtonHelper.setButton(0, true);
        }
    }

    public boolean shouldOverrideMouseOver() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return isEnabled()
            && basicCondition(minecraft)
            && attackingEntity != null
            && target == attackingEntity
            && targetDistance <= swingRange.getValue();
    }

    public void modifyMouseOverFromGetMouseOver(float partialTicks) {
        if (!shouldOverrideMouseOver()) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        Entity viewEntity = minecraft.getRenderViewEntity();
        if (viewEntity == null) {
            return;
        }

        Vec3 eyes = viewEntity.getPositionEyes(partialTicks);
        Vec3 look = viewEntity.getLook(partialTicks);
        double reach = attackRange.getValue();
        Vec3 rayEnd = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);

        float border = attackingEntity.getCollisionBorderSize();
        AxisAlignedBB bb = attackingEntity.getEntityBoundingBox().expand(border, border, border);
        MovingObjectPosition intercept = bb.calculateIntercept(eyes, rayEnd);
        boolean inside = bb.isVecInside(eyes);
        if (!inside && intercept == null) {
            return;
        }

        Vec3 hitVec = inside ? (intercept == null ? eyes : intercept.hitVec) : intercept.hitVec;
        MovingObjectPosition blockHit = minecraft.theWorld.rayTraceBlocks(eyes, hitVec, false, false, true);
        if (blockHit != null && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        if (!hitThroughEntities.isEnabled() && KillAuraRotationUtils.isPathBlockedByEntity(eyes, hitVec, attackingEntity)) {
            return;
        }

        minecraft.objectMouseOver = new MovingObjectPosition(attackingEntity, hitVec);
        minecraft.pointedEntity = attackingEntity;
        if (pointedEntityField != null) {
            try {
                pointedEntityField.set(minecraft.entityRenderer, attackingEntity);
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    private void handleTarget(Minecraft minecraft) {
        double maxRange = Math.max(attackRange.getValue(), aimRange.getValue());
        List<KillAuraTarget> candidates = new ArrayList<KillAuraTarget>();
        for (Object object : minecraft.theWorld.loadedEntityList) {
            if (!(object instanceof Entity)) {
                continue;
            }

            Candidate candidate = getCandidateTarget(minecraft, (Entity) object, maxRange);
            if (candidate == null) {
                continue;
            }

            KillAuraTarget auraTarget = buildKillAuraTarget(candidate.entity, candidate.distance, maxRange);
            if (auraTarget != null) {
                candidates.add(auraTarget);
            }
        }

        Collections.sort(candidates, Comparator.comparingDouble(new java.util.function.ToDoubleFunction<KillAuraTarget>() {
            @Override
            public double applyAsDouble(KillAuraTarget value) {
                return value.health;
            }
        }).thenComparingDouble(new java.util.function.ToDoubleFunction<KillAuraTarget>() {
            @Override
            public double applyAsDouble(KillAuraTarget value) {
                return value.distance;
            }
        }));

        double attackRangeValue = attackRange.getValue();
        List<KillAuraTarget> attackTargets = new ArrayList<KillAuraTarget>();
        for (KillAuraTarget candidate : candidates) {
            if (candidate.distance <= attackRangeValue) {
                attackTargets.add(candidate);
            }
        }

        if (!attackTargets.isEmpty()) {
            KillAuraTarget selectedAttackTarget = selectAttackTarget(minecraft, attackTargets);
            if (selectedAttackTarget != null) {
                setTarget(selectedAttackTarget.entity);
                return;
            }
            return;
        }

        if (!candidates.isEmpty()) {
            setTarget(candidates.get(0).entity);
            return;
        }

        setTarget(null);
    }

    private Candidate getCandidateTarget(Minecraft minecraft, Entity entity, double maxRange) {
        if (!(entity instanceof EntityPlayer) || entity == minecraft.thePlayer || entity.isDead) {
            return null;
        }

        EntityPlayer player = (EntityPlayer) entity;
        if (player.deathTime != 0 || player.getHealth() <= 0.0F || AntiBotModule.shouldIgnore(player)) {
            return null;
        }
        if (entity.isInvisible() && !targetInvis.isEnabled()) {
            return null;
        }

        double distance = KillAuraRotationUtils.distanceFromEyeToClosestOnAABB(entity);
        if (distance > maxRange) {
            return null;
        }

        return new Candidate(player, distance);
    }

    private KillAuraTarget buildKillAuraTarget(EntityLivingBase entity, double distanceToBoundingBox, double maxRange) {
        if (!KillAuraRotationUtils.hasValidAimPoint(entity, 100.0D, 100.0D, maxRange, false, hitThroughEntities.isEnabled())) {
            return null;
        }

        return new KillAuraTarget(entity, distanceToBoundingBox, entity.getHealth(), entity.getEntityId());
    }

    private KillAuraTarget selectAttackTarget(Minecraft minecraft, List<KillAuraTarget> attackTargets) {
        int ticksExisted = minecraft.thePlayer.ticksExisted;
        int switchDelayTicks = Math.max(1, switchDelay.getValue() / 50);
        long noHitTicks = (long) Math.min(attackTargets.size(), targets.getValue()) * switchDelayTicks;

        for (KillAuraTarget candidate : attackTargets) {
            Integer firstHitTick = hitMap.get(Integer.valueOf(candidate.entityId));
            if (firstHitTick == null || ticksExisted - firstHitTick.intValue() >= switchDelayTicks) {
                continue;
            }
            return candidate;
        }

        for (KillAuraTarget candidate : attackTargets) {
            Integer firstHitTick = hitMap.get(Integer.valueOf(candidate.entityId));
            if (firstHitTick == null || ticksExisted >= firstHitTick.intValue() + noHitTicks) {
                hitMap.put(Integer.valueOf(candidate.entityId), Integer.valueOf(ticksExisted));
                return candidate;
            }
        }

        return null;
    }

    private boolean basicCondition(Minecraft minecraft) {
        return minecraft != null
            && minecraft.thePlayer != null
            && minecraft.theWorld != null
            && !minecraft.thePlayer.isDead;
    }

    private boolean settingCondition(Minecraft minecraft) {
        if (disableInInventory.isEnabled() && minecraft.currentScreen != null) {
            return false;
        }
        if (weaponOnly.isEnabled() && !isHoldingWeapon(minecraft)) {
            return false;
        }
        return !disableWhileMining.isEnabled() || !isMining(minecraft);
    }

    private boolean isHoldingWeapon(Minecraft minecraft) {
        if (minecraft.thePlayer == null || minecraft.thePlayer.getHeldItem() == null) {
            return false;
        }

        Item item = minecraft.thePlayer.getHeldItem().getItem();
        return item instanceof ItemSword || item == Items.stick;
    }

    private boolean isMining(Minecraft minecraft) {
        int keyCode = minecraft.gameSettings.keyBindAttack.getKeyCode();
        if (keyCode == 0) {
            return false;
        }

        boolean attackDown = keyCode < 0 ? Mouse.isButtonDown(keyCode + 100) : Keyboard.isKeyDown(keyCode);
        if (!attackDown) {
            return false;
        }

        double reach = minecraft.playerController.getBlockReachDistance();
        Vec3 eyes = minecraft.thePlayer.getPositionEyes(1.0F);
        Vec3 look = KillAuraRotationUtils.getVectorForRotation(minecraft.thePlayer.rotationPitch, minecraft.thePlayer.rotationYaw);
        Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        if (rayTraceEntity(minecraft, eyes, end) != null) {
            return false;
        }

        MovingObjectPosition blockHit = minecraft.theWorld.rayTraceBlocks(eyes, end, false, false, false);
        return blockHit != null
            && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            && blockHit.getBlockPos() != null;
    }

    private Entity rayTraceEntity(Minecraft minecraft, Vec3 start, Vec3 end) {
        Vec3 delta = end.subtract(start);
        EntityPlayer player = minecraft.thePlayer;
        AxisAlignedBB searchBox = player.getEntityBoundingBox().addCoord(delta.xCoord, delta.yCoord, delta.zCoord).expand(1.0D, 1.0D, 1.0D);
        List<?> entities = minecraft.theWorld.getEntitiesWithinAABBExcludingEntity(player, searchBox);
        Entity closestEntity = null;
        double closestDistance = end.distanceTo(start);

        for (Object object : entities) {
            if (!(object instanceof Entity)) {
                continue;
            }

            Entity entity = (Entity) object;
            if (entity == player || !entity.canBeCollidedWith()) {
                continue;
            }

            float border = entity.getCollisionBorderSize();
            AxisAlignedBB bb = entity.getEntityBoundingBox().expand(border, border, border);
            MovingObjectPosition hit = bb.calculateIntercept(start, end);
            if (bb.isVecInside(start)) {
                return entity;
            }
            if (hit == null) {
                continue;
            }

            double distance = start.distanceTo(hit.hitVec);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestEntity = entity;
            }
        }

        return closestEntity;
    }

    private long nextDelay() {
        int cps = Math.max(1, (int) targetCps.getValue());
        int baseDelay = 1000 / cps;
        int finalDelay = baseDelay + (random.nextInt(21) - 10);
        return Math.max(33, Math.min(180, finalDelay));
    }

    private void setTarget(Entity entity) {
        if (!(entity instanceof EntityLivingBase)) {
            clearTargetState();
            return;
        }

        target = (EntityLivingBase) entity;
    }

    private void clearTargetState() {
        target = null;
        attackingEntity = null;
        targetDistance = Double.MAX_VALUE;
        nextClickTime = 0L;
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

    private static final class Candidate {
        private final EntityLivingBase entity;
        private final double distance;

        private Candidate(EntityLivingBase entity, double distance) {
            this.entity = entity;
            this.distance = distance;
        }
    }

    private static final class KillAuraTarget {
        private final EntityLivingBase entity;
        private final double distance;
        private final float health;
        private final int entityId;

        private KillAuraTarget(EntityLivingBase entity, double distance, float health, int entityId) {
            this.entity = entity;
            this.distance = distance;
            this.health = health;
            this.entityId = entityId;
        }
    }
}
