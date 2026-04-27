package com.lionclient.feature.module.impl;

import com.lionclient.combat.ClientRotationHelper;
import com.lionclient.event.ClientRotationEvent;
import com.lionclient.event.PrePlayerInteractEvent;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Keyboard;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class ClutchModule extends Module {
    private static final ClutchModule INSTANCE = new ClutchModule();
    private static final EnumFacing[] SEARCH_DIRECTIONS = new EnumFacing[] {
        EnumFacing.NORTH,
        EnumFacing.SOUTH,
        EnumFacing.EAST,
        EnumFacing.WEST
    };

    private final Minecraft mc = Minecraft.getMinecraft();

    private final EnumSetting<Trigger> trigger = new EnumSetting<Trigger>("Trigger", Trigger.values(), Trigger.ALWAYS);
    private final DecimalSetting blocks = new DecimalSetting("Blocks", 1.0D, 50.0D, 1.0D, 4.0D);
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false);
    private final BooleanSetting rotateBack = new BooleanSetting("Rotate Back", true);
    private final BooleanSetting returnToSlot = new BooleanSetting("Return To Slot", true);
    private final NumberSetting clutchMoveDelay = new NumberSetting("Clutch Move Delay", 0, 20, 1, 0);
    private final NumberSetting maxBlocks = new NumberSetting("Max Blocks", 1, 64, 1, 10);
    private final DecimalSetting rotationSpeed = new DecimalSetting("Rotation Speed", 10.0D, 120.0D, 1.0D, 60.0D);
    private final EnumSetting<FilterMode> filterMode = new EnumSetting<FilterMode>("Filter Mode", FilterMode.values(), FilterMode.NONE);

    private int blocksPlaced;
    private int savedSlot = -1;
    private int moveFreezeTicks;
    private boolean clutching;
    private boolean returningToCamera;
    private float savedCamYaw;
    private float savedCamPitch;
    private float currentYaw;
    private float currentPitch;
    private float targetYaw;
    private float targetPitch;
    private boolean rotationActive;
    private MovingObjectPosition pendingUseHit;
    private boolean forgeRegistered;

    private ClutchModule() {
        super("Clutch", "Bridges blocks back to safety when knocked off an edge", Category.PLAYER, Keyboard.KEY_NONE);

        blocks.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return trigger.getValue() == Trigger.FALL_DISTANCE;
            }
        });
        rotateBack.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return !silentAim.isEnabled();
            }
        });

        addSetting(trigger);
        addSetting(blocks);
        addSetting(silentAim);
        addSetting(rotateBack);
        addSetting(returnToSlot);
        addSetting(clutchMoveDelay);
        addSetting(maxBlocks);
        addSetting(rotationSpeed);
        addSetting(filterMode);
    }

    public static ClutchModule getInstance() {
        return INSTANCE;
    }

    @Override
    protected void onEnable() {
        resetState();
        registerForge();
    }

    @Override
    protected void onDisable() {
        unregisterForge();

        EntityPlayerSP player = mc.thePlayer;
        if (savedSlot != -1 && player != null && returnToSlot.isEnabled()) {
            setSelectedSlot(savedSlot);
        }

        resetState();
        clearRotationState();
    }

    @Override
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        pendingUseHit = null;
        if (!isPlayerReady()) {
            return;
        }

        EntityPlayerSP player = mc.thePlayer;
        if (moveFreezeTicks > 0) {
            moveFreezeTicks--;
            return;
        }

        if (player.onGround) {
            handleGroundState(player);
            return;
        }

        if (player.motionY >= 0.0D) {
            return;
        }
        if (!triggerMet(player)) {
            return;
        }
        if (!hasBlocks(player)) {
            return;
        }

        if (!clutching) {
            clutching = true;
            blocksPlaced = 0;
            returningToCamera = false;
            savedSlot = returnToSlot.isEnabled() ? player.inventory.currentItem : -1;
            savedCamYaw = player.rotationYaw;
            savedCamPitch = player.rotationPitch;
        }

        if (blocksPlaced >= maxBlocks.getValue()) {
            return;
        }
        if (!ensureHoldingBlock(player)) {
            return;
        }

        PlacementCandidate placement = findBestPlacement(player);
        if (placement == null) {
            return;
        }

        float[] aim = faceAim(player, placement.neighbor, placement.face);
        setRotationTarget(aim[0], aim[1]);
        stepRotation((float) rotationSpeed.getValue());
        applyVisibleRotation();

        MovingObjectPosition hit = rayTraceAtRotation(player, getReach(player), currentYaw, currentPitch);
        if (hit == null
            || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
            || !placement.neighbor.equals(hit.getBlockPos())
            || hit.sideHit != placement.face) {
            return;
        }

        mc.objectMouseOver = hit;
        pendingUseHit = hit;
        blocksPlaced++;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent event) {
        if (!isEnabled() || !silentAim.isEnabled() || !rotationActive || !isPlayerReady()) {
            return;
        }

        event.yaw = Float.valueOf(currentYaw);
        event.pitch = Float.valueOf(currentPitch);
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        if (pendingUseHit == null || !isPlayerReady()) {
            return;
        }

        EntityPlayerSP player = mc.thePlayer;
        ItemStack heldItem = player.getHeldItem();
        if (heldItem == null || !(heldItem.getItem() instanceof ItemBlock)) {
            pendingUseHit = null;
            return;
        }

        player.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(currentYaw, currentPitch, player.onGround));
        mc.objectMouseOver = pendingUseHit;
        KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
        pendingUseHit = null;
    }

    private void handleGroundState(EntityPlayerSP player) {
        if (clutching) {
            if (savedSlot != -1 && returnToSlot.isEnabled()) {
                setSelectedSlot(savedSlot);
                savedSlot = -1;
            }

            moveFreezeTicks = clutchMoveDelay.getValue();
            clutching = false;
            blocksPlaced = 0;

            if (rotateBack.isEnabled() && !silentAim.isEnabled()) {
                returningToCamera = true;
                setRotationTarget(savedCamYaw, savedCamPitch);
            } else {
                clearRotationState();
            }
        }

        if (!returningToCamera) {
            return;
        }

        stepRotation((float) rotationSpeed.getValue());
        applyVisibleRotation();

        if (hasReachedTarget(2.0F)) {
            clearRotationState();
            returningToCamera = false;
        }
    }

    private boolean triggerMet(EntityPlayerSP player) {
        int playerX = MathHelper.floor_double(player.posX);
        int playerZ = MathHelper.floor_double(player.posZ);
        int feetY = MathHelper.floor_double(player.posY);

        switch (trigger.getValue()) {
            case ALWAYS:
                return true;
            case ON_VOID:
                for (int y = feetY - 1; y >= feetY - 65; y--) {
                    if (isSupportBlock(new BlockPos(playerX, y, playerZ))) {
                        return false;
                    }
                }
                return true;
            case ON_LETHAL_FALL:
                int lethalDepth = 0;
                for (int y = feetY - 1; y >= feetY - 41; y--) {
                    if (isSupportBlock(new BlockPos(playerX, y, playerZ))) {
                        break;
                    }
                    lethalDepth++;
                }
                return player.fallDistance + lethalDepth - 3.0F >= player.getHealth() / 2.0F;
            case FALL_DISTANCE:
                int threshold = (int) blocks.getValue();
                int predictedDepth = 0;
                for (int y = feetY - 1; y >= feetY - threshold - 2; y--) {
                    if (isSupportBlock(new BlockPos(playerX, y, playerZ))) {
                        break;
                    }
                    predictedDepth++;
                }
                return player.fallDistance + predictedDepth >= blocks.getValue();
            default:
                return false;
        }
    }

    private int findBlockSlot(EntityPlayerSP player) {
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }

            Block block = ((ItemBlock) stack.getItem()).getBlock();
            if (block != null && block.isFullCube()) {
                return slot;
            }
        }

        return -1;
    }

    private boolean hasBlocks(EntityPlayerSP player) {
        return findBlockSlot(player) != -1;
    }

    private boolean ensureHoldingBlock(EntityPlayerSP player) {
        ItemStack heldItem = player.getHeldItem();
        if (heldItem != null && heldItem.getItem() instanceof ItemBlock) {
            return true;
        }

        int slot = findBlockSlot(player);
        if (slot == -1) {
            return false;
        }

        setSelectedSlot(slot);
        return true;
    }

    private PlacementCandidate findBestPlacement(EntityPlayerSP player) {
        double reach = getReach(player);
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;
        int blockX = MathHelper.floor_double(player.posX);
        int feetY = MathHelper.floor_double(player.posY);
        int blockZ = MathHelper.floor_double(player.posZ);
        double velocityY = Math.min(player.motionY, 0.0D);
        int targetY = MathHelper.floor_double(player.posY + velocityY) - 1;
        AxisAlignedBB trajectoryBox = player.getEntityBoundingBox().addCoord(0.0D, velocityY, 0.0D);

        PlacementCandidate best = null;
        for (int dy = 3; dy >= -4; dy--) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos airPos = new BlockPos(blockX + dx, feetY + dy, blockZ + dz);
                    if (!isAirBlock(airPos)) {
                        continue;
                    }

                    AxisAlignedBB blockBox = new AxisAlignedBB(
                        airPos.getX(),
                        airPos.getY(),
                        airPos.getZ(),
                        airPos.getX() + 1.0D,
                        airPos.getY() + 1.0D,
                        airPos.getZ() + 1.0D
                    );
                    if (trajectoryBox.intersectsWith(blockBox)) {
                        continue;
                    }

                    for (EnumFacing direction : SEARCH_DIRECTIONS) {
                        BlockPos neighbor = airPos.offset(direction);
                        if (!isAttachableBlock(neighbor)) {
                            continue;
                        }

                        EnumFacing face = direction.getOpposite();
                        double hitX = neighbor.getX() + 0.5D + face.getFrontOffsetX() * 0.45D;
                        double hitY = neighbor.getY() + 0.5D + face.getFrontOffsetY() * 0.45D;
                        double hitZ = neighbor.getZ() + 0.5D + face.getFrontOffsetZ() * 0.45D;
                        double deltaX = hitX - eyeX;
                        double deltaY = hitY - eyeY;
                        double deltaZ = hitZ - eyeZ;
                        if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > reach * reach) {
                            continue;
                        }

                        double yDeviation = Math.abs(airPos.getY() - targetY);
                        double horizontalDistance = Math.sqrt(
                            (airPos.getX() + 0.5D - player.posX) * (airPos.getX() + 0.5D - player.posX)
                                + (airPos.getZ() + 0.5D - player.posZ) * (airPos.getZ() + 0.5D - player.posZ)
                        );
                        double score = yDeviation * 20.0D + horizontalDistance;
                        if (best == null || score < best.score) {
                            best = new PlacementCandidate(neighbor, face, score);
                        }
                    }
                }
            }
        }

        return best;
    }

    private float[] faceAim(EntityPlayerSP player, BlockPos neighbor, EnumFacing face) {
        double eyeY = player.posY + player.getEyeHeight();
        double hitX = neighbor.getX() + 0.5D + face.getFrontOffsetX() * 0.45D;
        double hitY = neighbor.getY() + 0.5D + face.getFrontOffsetY() * 0.45D;
        double hitZ = neighbor.getZ() + 0.5D + face.getFrontOffsetZ() * 0.45D;
        double deltaX = hitX - player.posX;
        double deltaY = hitY - eyeY;
        double deltaZ = hitZ - player.posZ;
        double horizontalDistance = Math.max(0.01D, Math.sqrt(deltaX * deltaX + deltaZ * deltaZ));
        float yaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        float pitch = MathHelper.clamp_float((float) Math.toDegrees(Math.atan2(-deltaY, horizontalDistance)), -90.0F, 90.0F);
        return new float[] {yaw, pitch};
    }

    private void setRotationTarget(float yaw, float pitch) {
        if (!rotationActive && mc.thePlayer != null) {
            currentYaw = mc.thePlayer.rotationYaw;
            currentPitch = mc.thePlayer.rotationPitch;
        }

        targetYaw = yaw;
        targetPitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);
        rotationActive = true;
    }

    private void stepRotation(float maxDegrees) {
        if (!rotationActive || maxDegrees <= 0.0F || mc.gameSettings == null) {
            return;
        }

        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDiff = MathHelper.wrapAngleTo180_float(targetPitch - currentPitch);
        float distance = MathHelper.sqrt_float(yawDiff * yawDiff + pitchDiff * pitchDiff);
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = sensitivity * sensitivity * sensitivity * 1.2F;

        if (distance <= maxDegrees) {
            snapToTarget(gcd);
            return;
        }

        float ratio = maxDegrees / distance;
        int mouseDx = Math.round((yawDiff * ratio) / gcd);
        int mouseDy = Math.round((pitchDiff * ratio) / gcd);
        currentYaw += mouseDx * gcd;
        currentPitch = MathHelper.clamp_float(currentPitch + mouseDy * gcd, -90.0F, 90.0F);
    }

    private void snapToTarget(float gcd) {
        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;
        int mouseDx = Math.round(yawDiff / gcd);
        int mouseDy = Math.round(pitchDiff / gcd);
        currentYaw += mouseDx * gcd;
        currentPitch = MathHelper.clamp_float(currentPitch + mouseDy * gcd, -90.0F, 90.0F);
    }

    private boolean hasReachedTarget(float toleranceDegrees) {
        if (!rotationActive) {
            return true;
        }

        return Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - currentYaw)) <= toleranceDegrees
            && Math.abs(MathHelper.wrapAngleTo180_float(targetPitch - currentPitch)) <= toleranceDegrees;
    }

    private void applyVisibleRotation() {
        if (silentAim.isEnabled() || mc.thePlayer == null || !rotationActive) {
            return;
        }

        mc.thePlayer.rotationYaw = currentYaw;
        mc.thePlayer.rotationPitch = currentPitch;
    }

    private MovingObjectPosition rayTraceAtRotation(EntityPlayerSP player, double reach, float yaw, float pitch) {
        float savedYaw = player.rotationYaw;
        float savedPitch = player.rotationPitch;

        player.rotationYaw = yaw;
        player.rotationPitch = pitch;
        MovingObjectPosition hit = player.rayTrace(reach, 1.0F);
        player.rotationYaw = savedYaw;
        player.rotationPitch = savedPitch;
        return hit;
    }

    private double getReach(EntityPlayerSP player) {
        return player.capabilities.isCreativeMode ? 5.0D : 4.5D;
    }

    private boolean isAirBlock(BlockPos pos) {
        return mc.theWorld.getBlockState(pos).getBlock().getMaterial() == Material.air;
    }

    private boolean isSupportBlock(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block.getMaterial() != Material.air && !(block instanceof BlockLiquid);
    }

    private boolean isAttachableBlock(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block.getMaterial() != Material.air && !(block instanceof BlockLiquid);
    }

    private void setSelectedSlot(int slot) {
        if (!isPlayerReady() || mc.thePlayer.inventory.currentItem == slot) {
            return;
        }

        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(slot));
        mc.thePlayer.inventory.currentItem = slot;
    }

    private void clearRotationState() {
        rotationActive = false;
        targetYaw = 0.0F;
        targetPitch = 0.0F;
        ClientRotationHelper.get().clearRequestedRotations();
    }

    private void resetState() {
        blocksPlaced = 0;
        savedSlot = -1;
        moveFreezeTicks = 0;
        clutching = false;
        returningToCamera = false;
        pendingUseHit = null;
    }

    private boolean isPlayerReady() {
        return mc.thePlayer != null && mc.theWorld != null && !mc.thePlayer.isDead;
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

    public enum Trigger {
        ALWAYS,
        ON_VOID,
        ON_LETHAL_FALL,
        FALL_DISTANCE
    }

    public enum FilterMode {
        NONE,
        BLACKLIST,
        WHITELIST
    }

    private static final class PlacementCandidate {
        private final BlockPos neighbor;
        private final EnumFacing face;
        private final double score;

        private PlacementCandidate(BlockPos neighbor, EnumFacing face, double score) {
            this.neighbor = neighbor;
            this.face = face;
            this.score = score;
        }
    }
}
