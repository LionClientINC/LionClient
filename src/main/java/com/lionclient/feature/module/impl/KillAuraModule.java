package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public final class KillAuraModule extends Module {
    private static final java.lang.reflect.Field C03_YAW_FIELD = findC03Field("yaw", "field_149476_e");
    private static final java.lang.reflect.Field C03_PITCH_FIELD = findC03Field("pitch", "field_149473_f");
    private static final java.lang.reflect.Field C03_ROTATING_FIELD = findC03Field("rotating", "field_149481_i");

    private final java.lang.reflect.Field leftClickCounterField;
    private final DecimalSetting lookRange = new DecimalSetting("Look Range", 3.0D, 10.0D, 0.1D, 6.0D);
    private final DecimalSetting reach = new DecimalSetting("Reach", 3.0D, 6.0D, 0.1D, 3.0D);
    private final NumberSetting minCps = new NumberSetting("Min CPS", 1, 25, 1, 16);
    private final NumberSetting maxCps = new NumberSetting("Max CPS", 1, 25, 1, 21);
    private final EnumSetting<ClickMode> clickMode = new EnumSetting<ClickMode>("Click Mode", ClickMode.values(), ClickMode.NORMAL);
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", false);
    private final BooleanSetting fixMovement = new BooleanSetting("Movement Fix", true);
    private final BooleanSetting visuals = new BooleanSetting("Visuals", true);

    private EntityPlayer target;
    private List<EntityPlayer> playerTargets = new ArrayList<EntityPlayer>();
    private float serverYaw;
    private float serverPitch;
    private float desiredYaw;
    private float desiredPitch;
    private float currentYaw;
    private float currentPitch;
    private float previousYaw;
    private float previousPitch;
    private float movementYaw;
    private long lastClickAt;
    private long holdStartAt;
    private long blockReleaseAt;
    private boolean attackHeld;
    private boolean blocking;
    private boolean hasRotation;
    private double speedSeconds;
    private double holdLengthSeconds;
    private boolean stopClicker;
    private long recordNextClickTime;
    private int recordIndex;
    private boolean recordNoticeShown;

    public KillAuraModule() {
        super("KillAura [INVDEV]", "Attacks nearby players, use mode Record for strict anticheats like Polar.", Category.COMBAT, Keyboard.KEY_NONE);
        leftClickCounterField = findMinecraftField("field_71429_W", "leftClickCounter");
        minCps.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return clickMode.getValue() != ClickMode.RECORD;
            }
        });
        maxCps.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return clickMode.getValue() != ClickMode.RECORD;
            }
        });
        addSetting(lookRange);
        addSetting(reach);
        addSetting(minCps);
        addSetting(maxCps);
        addSetting(clickMode);
        addSetting(weaponOnly);
        addSetting(fixMovement);
        addSetting(visuals);
    }

    @Override
    protected void onEnable() {
        resetState();
        updateVals();
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            resetState();
            return;
        }

        normalizeRanges();

        if (minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            clearTargetState();
            return;
        }

        if (weaponOnly.isEnabled() && !isHoldingWeapon(minecraft)) {
            clearTargetState();
            return;
        }

        EntityPlayer nextTarget = getPreferredTarget(minecraft);
        if (nextTarget == null) {
            clearTargetState();
            return;
        }

        target = nextTarget;
        float[] rotations = getTargetRotations(minecraft.thePlayer, target);
        desiredYaw = rotations[0];
        desiredPitch = rotations[1];
        updateRotationState(minecraft);
        handleBlock(minecraft);
        if (clickMode.getValue() != ClickMode.RECORD) {
            handleAttack(minecraft);
        }
    }

    @Override
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (clickMode.getValue() != ClickMode.RECORD) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null || target == null) {
            return;
        }

        if (minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            return;
        }

        removeClickDelay(minecraft);
        handleRecordAttack(minecraft, System.currentTimeMillis());
    }

    @Override
    public void onPlayerTick(net.minecraftforge.fml.common.gameevent.TickEvent.PlayerTickEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || event.player != minecraft.thePlayer || !hasRotation) {
            return;
        }

        if (event.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.START) {
            applyMovementFix(minecraft);
            return;
        }

        applyRenderRotations(minecraft);
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!visuals.isEnabled() || target == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }

        drawTargetBox(minecraft, target, event.partialTicks);
    }

    private void handleAttack(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        if (now - lastClickAt > speedSeconds * 1000.0D) {
            lastClickAt = now;
            if (holdStartAt < lastClickAt) {
                holdStartAt = lastClickAt;
            }
            performLegitAttack(minecraft);
            attackHeld = true;
            stopClicker = false;
            updateVals();
        } else if (attackHeld && now - holdStartAt > holdLengthSeconds * 1000.0D) {
            sendAttackKey(minecraft, false);
            attackHeld = false;
            updateVals();
        }
    }

    private void handleRecordAttack(Minecraft minecraft, long now) {
        List<Integer> delays = ClickPatternStore.getDelays();
        if (delays.isEmpty()) {
            if (!recordNoticeShown) {
                sendChat("No recorded pattern. Use ClickRecorder in CLIENT first.");
                recordNoticeShown = true;
            }
            return;
        }

        if (recordNextClickTime < 0L) {
            recordNextClickTime = now;
        }

        if (attackHeld) {
            sendAttackKey(minecraft, false);
            attackHeld = false;
        }

        if (now < recordNextClickTime) {
            return;
        }

        performRecordedAttack(minecraft);

        recordIndex++;
        if (recordIndex >= delays.size()) {
            recordIndex = 0;
        }

        recordNextClickTime = now + Math.max(0, delays.get(recordIndex).intValue());
        recordNoticeShown = false;
    }

    private void handleBlock(Minecraft minecraft) {
        if (target == null || !isHoldingSword(minecraft) || !canAttackTarget(minecraft, target)) {
            unblock(minecraft);
            return;
        }

        long now = System.currentTimeMillis();
        if (blocking && now >= blockReleaseAt) {
            unblock(minecraft);
        }

        if (minecraft.thePlayer.swingProgress > minecraft.thePlayer.prevSwingProgress && minecraft.thePlayer.ticksExisted % 15 == 0) {
            legitBlock(minecraft, now);
        }
    }

    private void legitBlock(Minecraft minecraft, long now) {
        int useKey = minecraft.gameSettings.keyBindUseItem.getKeyCode();
        sendUseItem(minecraft.thePlayer, minecraft.theWorld, minecraft.thePlayer.getHeldItem());
        KeyBinding.setKeyBindState(useKey, true);
        KeyBinding.onTick(useKey);
        blocking = true;
        blockReleaseAt = now + 80L;
    }

    private EntityPlayer findTarget(Minecraft minecraft) {
        EntityPlayer self = minecraft.thePlayer;
        EntityPlayer bestTarget = null;
        double bestDistance = Math.max(3.0D, lookRange.getValue());
        float bestFov = 180.0F;
        List<EntityPlayer> candidates = new ArrayList<EntityPlayer>();

        for (Object object : minecraft.theWorld.playerEntities) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }

            EntityPlayer player = (EntityPlayer) object;
            if (!isValidTarget(self, player)) {
                continue;
            }

            double distance = self.getDistanceToEntity(player);
            if (distance > bestDistance || !self.canEntityBeSeen(player)) {
                continue;
            }

            candidates.add(player);
            float[] rotations = getTargetRotations(self, player);
            float fov = Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - self.rotationYaw))
                + Math.abs(rotations[1] - self.rotationPitch) * 0.35F;
            if (bestTarget != null && distance > bestDistance - 0.15D && fov >= bestFov) {
                continue;
            }

            bestDistance = distance;
            bestFov = fov;
            bestTarget = player;
        }

        playerTargets = candidates;
        return bestTarget;
    }

    private EntityPlayer getPreferredTarget(Minecraft minecraft) {
        if (canTrackTarget(minecraft, target)) {
            return target;
        }
        return findTarget(minecraft);
    }

    private boolean canTrackTarget(Minecraft minecraft, EntityPlayer player) {
        return isValidTarget(minecraft.thePlayer, player)
            && minecraft.thePlayer.getDistanceToEntity(player) <= Math.max(3.0D, lookRange.getValue())
            && minecraft.thePlayer.canEntityBeSeen(player);
    }

    private boolean isValidTarget(EntityPlayer self, EntityPlayer player) {
        return player != null
            && player != self
            && !player.isDead
            && player.getHealth() > 0.0F
            && !player.isInvisible()
            && !AntiBotModule.shouldIgnore(player);
    }

    private void updateRotationState(Minecraft minecraft) {
        EntityPlayerSP player = minecraft.thePlayer;
        movementYaw = player.rotationYaw;
        previousYaw = currentYaw;
        previousPitch = currentPitch;

        float nextYaw = desiredYaw;
        float nextPitch = desiredPitch;
        float baseYaw = hasRotation ? currentYaw : player.rotationYaw;
        float basePitch = hasRotation ? currentPitch : player.rotationPitch;
        nextYaw = maxAngleChange(baseYaw, nextYaw, 100.0F);
        nextPitch = maxAngleChange(basePitch, nextPitch, 100.0F);

        float[] snapped = applyGcd(minecraft, nextYaw, nextPitch, hasRotation ? currentYaw : player.rotationYaw, hasRotation ? currentPitch : player.rotationPitch);
        currentYaw = snapped[0];
        currentPitch = snapped[1];
        serverYaw = currentYaw;
        serverPitch = currentPitch;
        if (!hasRotation) {
            previousYaw = currentYaw;
            previousPitch = currentPitch;
        }
        hasRotation = true;
    }

    private void applyRenderRotations(Minecraft minecraft) {
        EntityPlayerSP player = minecraft.thePlayer;
        player.prevRotationYawHead = previousYaw;
        player.prevRenderYawOffset = previousYaw;
        player.rotationYawHead = currentYaw;
        player.renderYawOffset = currentYaw;
    }

    @Override
    public void onOutboundPacket(net.minecraft.network.Packet<?> packet) {
        if (!hasRotation || !(packet instanceof C03PacketPlayer)) {
            return;
        }

        C03PacketPlayer playerPacket = (C03PacketPlayer) packet;
        setField(C03_YAW_FIELD, playerPacket, Float.valueOf(serverYaw));
        setField(C03_PITCH_FIELD, playerPacket, Float.valueOf(serverPitch));
        setField(C03_ROTATING_FIELD, playerPacket, Boolean.TRUE);
    }

    private void applyMovementFix(Minecraft minecraft) {
        if (!fixMovement.isEnabled() || !hasRotation || target == null || !minecraft.thePlayer.onGround) {
            return;
        }

        EntityPlayerSP player = minecraft.thePlayer;
        if (player.movementInput == null) {
            return;
        }

        float forward = player.movementInput.moveForward;
        float strafe = player.movementInput.moveStrafe;
        if (forward == 0.0F && strafe == 0.0F) {
            return;
        }

        float delta = MathHelper.wrapAngleTo180_float(currentYaw - movementYaw);
        float radians = delta * (float) Math.PI / 180.0F;
        float cosine = MathHelper.cos(radians);
        float sine = MathHelper.sin(radians);
        float adjustedForward = forward * cosine + strafe * sine;
        float adjustedStrafe = strafe * cosine - forward * sine;
        float clampedForward = clampMovement(adjustedForward);
        float clampedStrafe = clampMovement(adjustedStrafe);
        player.movementInput.moveForward = clampedForward;
        player.movementInput.moveStrafe = clampedStrafe;
        player.moveForward = clampedForward;
        player.moveStrafing = clampedStrafe;
    }

    private float[] getTargetRotations(EntityPlayer source, EntityPlayer entity) {
        double diffX = entity.posX - source.posX;
        double diffZ = entity.posZ - source.posZ;
        double diffY = entity.getEntityBoundingBox().minY + entity.height * 0.9D - (source.posY + source.getEyeHeight());
        double distance = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float yaw = MathHelper.wrapAngleTo180_float((float) (Math.atan2(diffZ, diffX) * 180.0D / Math.PI) - 90.0F);
        float pitch = MathHelper.clamp_float((float) (-(Math.atan2(diffY, distance) * 180.0D / Math.PI)), -90.0F, 90.0F);
        return new float[]{yaw, pitch};
    }

    private float[] applyGcd(Minecraft minecraft, float yaw, float pitch, float previousYawValue, float previousPitchValue) {
        float yawDiff = yaw - previousYawValue;
        float pitchDiff = pitch - previousPitchValue;
        double gcd = getSensitivityStep(minecraft);
        yaw -= (float) (yawDiff % gcd);
        pitch -= (float) (pitchDiff % gcd);
        return new float[]{yaw, pitch};
    }

    private double getSensitivityStep(Minecraft minecraft) {
        float sensitivity = minecraft.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float cubic = sensitivity * sensitivity * sensitivity * 8.0F;
        return cubic * 0.15D;
    }

    private float maxAngleChange(float previous, float current, float maxTurn) {
        float difference = MathHelper.wrapAngleTo180_float(current - previous);
        if (difference > maxTurn) {
            difference = maxTurn;
        }
        if (difference < -maxTurn) {
            difference = -maxTurn;
        }
        return previous + difference;
    }

    private boolean canAttackTarget(Minecraft minecraft, EntityPlayer player) {
        if (!isValidTarget(minecraft.thePlayer, player)) {
            return false;
        }

        return minecraft.thePlayer.getDistanceToEntity(player) <= Math.max(3.0D, reach.getValue())
            && minecraft.thePlayer.canEntityBeSeen(player)
            && raycastEntity(minecraft, Math.max(3.0D, reach.getValue())) == player;
    }

    private Entity raycastEntity(Minecraft minecraft, double range) {
        EntityPlayerSP player = minecraft.thePlayer;
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 look = getVectorForRotation(currentPitch, currentYaw);
        Vec3 reachVector = eyes.addVector(look.xCoord * range, look.yCoord * range, look.zCoord * range);
        Entity pointedEntity = null;
        double bestDistance = range;

        for (Object object : minecraft.theWorld.getEntitiesWithinAABBExcludingEntity(
            player,
            player.getEntityBoundingBox().addCoord(look.xCoord * range, look.yCoord * range, look.zCoord * range).expand(1.0D, 1.0D, 1.0D)
        )) {
            if (!(object instanceof Entity)) {
                continue;
            }

            Entity entity = (Entity) object;
            if (!(entity instanceof EntityPlayer) || entity == player || !entity.canBeCollidedWith()) {
                continue;
            }

            float border = entity.getCollisionBorderSize();
            AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
            net.minecraft.util.MovingObjectPosition intercept = box.calculateIntercept(eyes, reachVector);

            if (box.isVecInside(eyes)) {
                if (bestDistance >= 0.0D) {
                    pointedEntity = entity;
                    bestDistance = 0.0D;
                }
                continue;
            }

            if (intercept == null) {
                continue;
            }

            double distance = eyes.distanceTo(intercept.hitVec);
            if (distance < bestDistance || bestDistance == 0.0D) {
                pointedEntity = entity;
                bestDistance = distance;
            }
        }

        return pointedEntity;
    }

    private Vec3 getVectorForRotation(float pitch, float yaw) {
        float pitchCos = MathHelper.cos(-pitch * 0.017453292F);
        float pitchSin = MathHelper.sin(-pitch * 0.017453292F);
        float yawCos = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float yawSin = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        return new Vec3(yawSin * pitchCos, pitchSin, yawCos * pitchCos);
    }

    private void sendAttackKey(Minecraft minecraft, boolean pressed) {
        int keyCode = minecraft.gameSettings.keyBindAttack.getKeyCode();
        KeyBinding.setKeyBindState(keyCode, pressed);
        if (pressed) {
            KeyBinding.onTick(keyCode);
        }
    }

    private void performRecordedAttack(Minecraft minecraft) {
        sendRecordedClick(minecraft, true);
        if (target != null && canAttackTarget(minecraft, target)) {
            minecraft.thePlayer.swingItem();
            minecraft.playerController.attackEntity(minecraft.thePlayer, target);
        }
        sendRecordedClick(minecraft, false);
    }

    private void performLegitAttack(Minecraft minecraft) {
        sendAttackKey(minecraft, true);
        if (target == null || !canAttackTarget(minecraft, target)) {
            return;
        }

        minecraft.thePlayer.swingItem();
        minecraft.playerController.attackEntity(minecraft.thePlayer, target);
    }

    private boolean isHoldingWeapon(Minecraft minecraft) {
        ItemStack heldItem = minecraft.thePlayer.getHeldItem();
        if (heldItem == null) {
            return false;
        }

        String name = heldItem.getUnlocalizedName();
        return name != null && (name.contains("sword") || name.contains("axe"));
    }

    private boolean isHoldingSword(Minecraft minecraft) {
        ItemStack heldItem = minecraft.thePlayer.getHeldItem();
        if (heldItem == null) {
            return false;
        }

        String name = heldItem.getUnlocalizedName();
        return name != null && name.contains("sword");
    }

    private void block(Minecraft minecraft) {
        if (blocking || minecraft.thePlayer.getHeldItem() == null) {
            return;
        }

        sendUseItem(minecraft.thePlayer, minecraft.theWorld, minecraft.thePlayer.getHeldItem());
        KeyBinding.setKeyBindState(minecraft.gameSettings.keyBindUseItem.getKeyCode(), true);
        minecraft.thePlayer.sendQueue.addToSendQueue(
            new C08PacketPlayerBlockPlacement(new BlockPos(-1, -1, -1), 255, minecraft.thePlayer.getHeldItem(), 0.0F, 0.0F, 0.0F)
        );
        blocking = true;
    }

    private void unblock(Minecraft minecraft) {
        if (!blocking) {
            return;
        }

        KeyBinding.setKeyBindState(minecraft.gameSettings.keyBindUseItem.getKeyCode(), false);
        minecraft.getNetHandler().addToSendQueue(
            new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN)
        );
        blocking = false;
    }

    private void sendUseItem(EntityPlayer player, World world, ItemStack stack) {
        if (player == null || stack == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.playerController.getCurrentGameType() == WorldSettings.GameType.SPECTATOR) {
            return;
        }

        int size = stack.stackSize;
        ItemStack result = stack.useItemRightClick(world, player);
        if (result != stack || result.stackSize != size) {
            player.inventory.mainInventory[player.inventory.currentItem] = result;
            if (result != null && result.stackSize == 0) {
                player.inventory.mainInventory[player.inventory.currentItem] = null;
            }
        }
    }

    private void drawTargetBox(Minecraft minecraft, EntityPlayer player, float partialTicks) {
        int red = (int) Math.min(255.0F, (20.0F - player.getHealth()) * 13.0F);
        int green = 255 - red;
        drawEntityBox(minecraft, player, partialTicks, red / 255.0F, green / 255.0F, 0.0F, 1.0F, 2.0F);

        for (EntityPlayer candidate : playerTargets) {
            if (candidate == null || candidate == player) {
                continue;
            }
            drawEntityBox(minecraft, candidate, partialTicks, 0.0F, 0.0F, 1.0F, 0.5F, 1.6F);
        }
    }

    private void drawEntityBox(Minecraft minecraft, EntityPlayer player, float partialTicks, float red, float green, float blue, float alpha, float lineWidth) {
        if (player == null) {
            return;
        }

        double viewerX = minecraft.getRenderManager().viewerPosX;
        double viewerY = minecraft.getRenderManager().viewerPosY;
        double viewerZ = minecraft.getRenderManager().viewerPosZ;

        double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks - viewerX;
        double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks - viewerY;
        double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks - viewerZ;
        AxisAlignedBB bb = player.getEntityBoundingBox();
        AxisAlignedBB renderBox = new AxisAlignedBB(
            bb.minX - player.posX + x,
            bb.minY - player.posY + y,
            bb.minZ - player.posZ + z,
            bb.maxX - player.posX + x,
            bb.maxY - player.posY + y,
            bb.maxZ - player.posZ + z
        ).expand(0.05D, 0.1D, 0.05D);

        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glLineWidth(lineWidth);
        GL11.glColor4f(red, green, blue, alpha);
        GL11.glBegin(GL11.GL_LINES);

        vertex(renderBox.minX, renderBox.minY, renderBox.minZ, renderBox.maxX, renderBox.minY, renderBox.minZ);
        vertex(renderBox.maxX, renderBox.minY, renderBox.minZ, renderBox.maxX, renderBox.minY, renderBox.maxZ);
        vertex(renderBox.maxX, renderBox.minY, renderBox.maxZ, renderBox.minX, renderBox.minY, renderBox.maxZ);
        vertex(renderBox.minX, renderBox.minY, renderBox.maxZ, renderBox.minX, renderBox.minY, renderBox.minZ);

        vertex(renderBox.minX, renderBox.maxY, renderBox.minZ, renderBox.maxX, renderBox.maxY, renderBox.minZ);
        vertex(renderBox.maxX, renderBox.maxY, renderBox.minZ, renderBox.maxX, renderBox.maxY, renderBox.maxZ);
        vertex(renderBox.maxX, renderBox.maxY, renderBox.maxZ, renderBox.minX, renderBox.maxY, renderBox.maxZ);
        vertex(renderBox.minX, renderBox.maxY, renderBox.maxZ, renderBox.minX, renderBox.maxY, renderBox.minZ);

        vertex(renderBox.minX, renderBox.minY, renderBox.minZ, renderBox.minX, renderBox.maxY, renderBox.minZ);
        vertex(renderBox.maxX, renderBox.minY, renderBox.minZ, renderBox.maxX, renderBox.maxY, renderBox.minZ);
        vertex(renderBox.maxX, renderBox.minY, renderBox.maxZ, renderBox.maxX, renderBox.maxY, renderBox.maxZ);
        vertex(renderBox.minX, renderBox.minY, renderBox.maxZ, renderBox.minX, renderBox.maxY, renderBox.maxZ);

        GL11.glEnd();
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }

    private void vertex(double x1, double y1, double z1, double x2, double y2, double z2) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
    }

    private float clampMovement(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
    }

    private void normalizeRanges() {
        if (maxCps.getValue() < minCps.getValue()) {
            maxCps.setValue(minCps.getValue());
        }
    }

    private void sendRecordedClick(Minecraft minecraft, boolean pressed) {
        int keyCode = minecraft.gameSettings.keyBindAttack.getKeyCode();
        KeyBinding.setKeyBindState(keyCode, pressed);
        setMouseButtonState(0, pressed);
        if (pressed) {
            KeyBinding.onTick(keyCode);
        }
    }

    private void setMouseButtonState(int mouseButton, boolean held) {
        MouseEvent event = new MouseEvent();
        ObfuscationReflectionHelper.setPrivateValue(MouseEvent.class, event, Integer.valueOf(mouseButton), "button");
        ObfuscationReflectionHelper.setPrivateValue(MouseEvent.class, event, Boolean.valueOf(held), "buttonstate");
        MinecraftForge.EVENT_BUS.post(event);

        ByteBuffer buttons = ObfuscationReflectionHelper.getPrivateValue(Mouse.class, null, "buttons");
        if (buttons != null && buttons.capacity() > mouseButton) {
            buttons.put(mouseButton, (byte) (held ? 1 : 0));
            ObfuscationReflectionHelper.setPrivateValue(Mouse.class, null, buttons, "buttons");
        }
    }

    private void removeClickDelay(Minecraft minecraft) {
        if (leftClickCounterField == null || !minecraft.inGameHasFocus || minecraft.thePlayer.capabilities.isCreativeMode) {
            return;
        }

        try {
            leftClickCounterField.setInt(minecraft, 0);
        } catch (IllegalAccessException ignored) {
        }
    }

    private void updateVals() {
        stopClicker = false;
        double min = minCps.getValue();
        double max = Math.max(min, maxCps.getValue());
        if (min >= max) {
            max = min + 1.0D;
        }

        speedSeconds = 1.0D / ThreadLocalRandom.current().nextDouble(Math.max(1.0D, min - 0.2D), max);
        holdLengthSeconds = speedSeconds / ThreadLocalRandom.current().nextDouble(min, max);
    }

    private void clearTargetState() {
        target = null;
        playerTargets = new ArrayList<EntityPlayer>();
        hasRotation = false;
        recordNextClickTime = -1L;
        recordIndex = 0;
        recordNoticeShown = false;
        if (attackHeld) {
            sendAttackKey(Minecraft.getMinecraft(), false);
        }
        if (!stopClicker) {
            stopClicker = true;
        }
        attackHeld = false;
        unblock(Minecraft.getMinecraft());
    }

    private void resetState() {
        desiredYaw = 0.0F;
        desiredPitch = 0.0F;
        serverYaw = 0.0F;
        serverPitch = 0.0F;
        currentYaw = 0.0F;
        currentPitch = 0.0F;
        previousYaw = 0.0F;
        previousPitch = 0.0F;
        movementYaw = 0.0F;
        lastClickAt = 0L;
        holdStartAt = 0L;
        blockReleaseAt = 0L;
        speedSeconds = 0.0D;
        holdLengthSeconds = 0.0D;
        stopClicker = false;
        recordNextClickTime = -1L;
        recordIndex = 0;
        recordNoticeShown = false;
        clearTargetState();
    }

    private void sendChat(String text) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer != null) {
            minecraft.thePlayer.addChatMessage(new ChatComponentText("[KillAura] " + text));
        }
    }

    private static java.lang.reflect.Field findC03Field(String... names) {
        try {
            java.lang.reflect.Field field = ReflectionHelper.findField(C03PacketPlayer.class, names);
            field.setAccessible(true);
            return field;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Field findMinecraftField(String... names) {
        try {
            java.lang.reflect.Field field = ReflectionHelper.findField(Minecraft.class, names);
            field.setAccessible(true);
            return field;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void setField(java.lang.reflect.Field field, Object target, Object value) {
        if (field == null || target == null) {
            return;
        }

        try {
            field.set(target, value);
        } catch (IllegalAccessException ignored) {
        }
    }

    private enum ClickMode {
        NORMAL,
        RECORD
    }
}
