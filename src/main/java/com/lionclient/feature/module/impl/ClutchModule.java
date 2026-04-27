package com.lionclient.feature.module.impl;

import com.lionclient.combat.ClientRotationHelper;
import com.lionclient.event.ClientRotationEvent;
import com.lionclient.event.PrePlayerInputEvent;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
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
    private int groundedClutchTicks;
    private boolean clutching;
    private boolean returningToCamera;
    private float savedCamYaw;
    private float savedCamPitch;
    private float currentYaw;
    private float currentPitch;
    private float targetYaw;
    private float targetPitch;
    private boolean rotationActive;
    private int rotationHeldTicks;
    private List<BlockPos> bridgePath;
    private int bridgeIndex;
    private PlacementCandidate bridgeStartPlacement;
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
        groundedClutchTicks = 0;

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
            rotationHeldTicks = 0;
            bridgeStartPlacement = null;
            refreshBridgePlan(player);
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

        if (bridgePath == null || bridgeIndex >= bridgePath.size()) {
            refreshBridgePlan(player);
        }

        PlacementCandidate placement = findBridgePlacement(player);
        if (placement == null) {
            refreshBridgePlan(player);
            placement = findBridgePlacement(player);
        }
        if (placement == null) {
            placement = findBestPlacement(player);
        }
        if (placement == null) {
            return;
        }

        float[] aim = faceAim(player, placement.neighbor, placement.face);
        setRotationTarget(aim[0], aim[1]);
        stepRotation((float) rotationSpeed.getValue());
        applyVisibleRotation();

        if (!hasReachedTarget(2.0F)) {
            rotationHeldTicks = 0;
            return;
        }

        rotationHeldTicks = Math.min(2, rotationHeldTicks + 1);
        if (rotationHeldTicks < 2) {
            return;
        }

        MovingObjectPosition hit = rayTraceAtRotation(player, getReach(player), currentYaw, currentPitch);
        if (hit == null
            || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
            || !placement.neighbor.equals(hit.getBlockPos())
            || hit.sideHit != placement.face) {
            return;
        }

        if (attemptPlacement(player, hit)) {
            advanceBridgeState(placement);
            blocksPlaced++;
        }
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
    public void onPrePlayerInput(PrePlayerInputEvent event) {
        if (!shouldLockMovement()) {
            return;
        }

        event.setForward(0.0F);
        event.setStrafe(0.0F);
    }

    private void handleGroundState(EntityPlayerSP player) {
        if (clutching) {
            applyImmediateLandingLock(player);
            groundedClutchTicks++;
            if (groundedClutchTicks < 4) {
                return;
            }
            groundedClutchTicks = 0;
            if (savedSlot != -1 && returnToSlot.isEnabled()) {
                setSelectedSlot(savedSlot);
                savedSlot = -1;
            }

            moveFreezeTicks = Math.max(1, clutchMoveDelay.getValue());
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
        int minDy = blocksPlaced > 0 ? -4 : -2;
        for (int dy = 0; dy >= minDy; dy--) {
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
                        Block neighborBlock = mc.theWorld.getBlockState(neighbor).getBlock();
                        if (neighborBlock instanceof BlockLiquid) {
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
                        if (dx == 0 && dz == 0) {
                            score += 6.0D;
                        }
                        if (best == null || score < best.score) {
                            best = new PlacementCandidate(airPos, neighbor, face, score);
                        }
                    }
                }
            }
        }

        return best;
    }

    private PlacementCandidate findBridgePlacement(EntityPlayerSP player) {
        if (bridgePath == null || bridgeIndex >= bridgePath.size()) {
            return null;
        }

        while (bridgeIndex < bridgePath.size()) {
            BlockPos target = bridgePath.get(bridgeIndex);
            if (!isAirBlock(target)) {
                bridgeIndex++;
                continue;
            }

            PlacementCandidate placement;
            if (bridgeIndex == 0 && bridgeStartPlacement != null) {
                placement = bridgeStartPlacement;
            } else {
                placement = null;
                if (bridgeIndex > 0) {
                    placement = makePlacementAgainst(target, bridgePath.get(bridgeIndex - 1));
                }
                if (placement == null) {
                    placement = findPlacementInfo(target);
                }
            }

            if (placement == null) {
                return null;
            }
            if (!isPlacementReachable(player, placement.neighbor, placement.face, getReach(player))) {
                return null;
            }

            return placement;
        }

        return null;
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

    private boolean attemptPlacement(EntityPlayerSP player, MovingObjectPosition hit) {
        if (mc.playerController == null || hit == null || hit.hitVec == null) {
            return false;
        }

        ItemStack heldItem = player.getHeldItem();
        if (heldItem == null || !(heldItem.getItem() instanceof ItemBlock)) {
            return false;
        }

        mc.objectMouseOver = hit;

        if (mc.playerController.onPlayerRightClick(player, mc.theWorld, heldItem, hit.getBlockPos(), hit.sideHit, hit.hitVec)) {
            player.swingItem();
            return true;
        }

        return false;
    }

    private void advanceBridgeState(PlacementCandidate placement) {
        if (bridgePath == null || bridgeIndex >= bridgePath.size() || placement == null || placement.targetPos == null) {
            return;
        }

        if (placement.targetPos.equals(bridgePath.get(bridgeIndex))) {
            bridgeIndex++;
            if (bridgeIndex > 0) {
                bridgeStartPlacement = null;
            }
        }
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

    private boolean isPlacementReachable(EntityPlayerSP player, BlockPos neighbor, EnumFacing face, double reach) {
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;
        double hitX = neighbor.getX() + 0.5D + face.getFrontOffsetX() * 0.45D;
        double hitY = neighbor.getY() + 0.5D + face.getFrontOffsetY() * 0.45D;
        double hitZ = neighbor.getZ() + 0.5D + face.getFrontOffsetZ() * 0.45D;
        double deltaX = hitX - eyeX;
        double deltaY = hitY - eyeY;
        double deltaZ = hitZ - eyeZ;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= reach * reach;
    }

    private List<BlockPos> buildBridgePath(EntityPlayerSP player) {
        PlacementCandidate edge = findNearestEdge(player);
        if (edge == null || edge.targetPos == null) {
            return null;
        }

        double futureX = player.posX + player.motionX;
        double futureZ = player.posZ + player.motionZ;
        int playerX = MathHelper.floor_double(futureX);
        int playerZ = MathHelper.floor_double(futureZ);
        int estimatedLength = Math.abs(edge.targetPos.getX() - playerX) + Math.abs(edge.targetPos.getZ() - playerZ);
        if (estimatedLength < 1) {
            estimatedLength = 1;
        }

        double predictedY = predictYAfterTicks(player, estimatedLength);
        int bridgeY = MathHelper.floor_double(predictedY) - 1;
        int supportY = edge.neighbor.getY();
        if (bridgeY > supportY) {
            bridgeY = supportY;
        }

        BlockPos bridgeStart = new BlockPos(edge.targetPos.getX(), bridgeY, edge.targetPos.getZ());
        BlockPos playerColumn = new BlockPos(playerX, bridgeY, playerZ);
        List<BlockPos> path = calculateBridgePath(bridgeStart, playerColumn);
        if (path.isEmpty()) {
            return null;
        }

        double fracX = futureX - Math.floor(futureX);
        double fracZ = futureZ - Math.floor(futureZ);
        BlockPos last = path.get(path.size() - 1);
        if (fracX >= 0.7D) {
            path.add(new BlockPos(last.getX() + 1, bridgeY, last.getZ()));
        } else if (fracX <= 0.3D) {
            path.add(new BlockPos(last.getX() - 1, bridgeY, last.getZ()));
        }

        last = path.get(path.size() - 1);
        if (fracZ >= 0.7D) {
            path.add(new BlockPos(last.getX(), bridgeY, last.getZ() + 1));
        } else if (fracZ <= 0.3D) {
            path.add(new BlockPos(last.getX(), bridgeY, last.getZ() - 1));
        }

        int availableBlocks = countAvailableBlocks(player);
        if (path.size() > availableBlocks) {
            path = new ArrayList<BlockPos>(path.subList(0, availableBlocks));
        }
        if (path.isEmpty()) {
            return null;
        }

        PlacementCandidate startPlacement = findPlacementInfo(path.get(0));
        if (startPlacement != null) {
            bridgeStartPlacement = startPlacement;
        } else {
            bridgeStartPlacement = new PlacementCandidate(path.get(0), edge.neighbor, edge.face, edge.score);
        }
        return path;
    }

    private void refreshBridgePlan(EntityPlayerSP player) {
        bridgeStartPlacement = null;
        bridgePath = buildBridgePath(player);
        bridgeIndex = 0;
    }

    private PlacementCandidate findNearestEdge(EntityPlayerSP player) {
        int playerX = MathHelper.floor_double(player.posX);
        int playerY = MathHelper.floor_double(player.posY);
        int playerZ = MathHelper.floor_double(player.posZ);
        PlacementCandidate best = null;
        double bestScore = Double.MAX_VALUE;
        int range = 4;
        double reach = getReach(player);

        for (int dy = -4; dy <= 1; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos solidPos = new BlockPos(playerX + dx, playerY + dy, playerZ + dz);
                    if (!isAttachableBlock(solidPos)) {
                        continue;
                    }

                    for (EnumFacing face : EnumFacing.values()) {
                        BlockPos airPos = solidPos.offset(face);
                        if (!isAirBlock(airPos)) {
                            continue;
                        }
                        if (!isPlacementReachable(player, solidPos, face, reach)) {
                            continue;
                        }

                        double horizontalDistance = Math.sqrt(
                            (airPos.getX() + 0.5D - player.posX) * (airPos.getX() + 0.5D - player.posX)
                                + (airPos.getZ() + 0.5D - player.posZ) * (airPos.getZ() + 0.5D - player.posZ)
                        );
                        double verticalDistance = Math.abs(airPos.getY() - (playerY - 1));
                        double score = horizontalDistance + verticalDistance * 3.0D;
                        if (best == null || score < bestScore) {
                            bestScore = score;
                            best = new PlacementCandidate(airPos, solidPos, face, score);
                        }
                    }
                }
            }
        }

        return best;
    }

    private PlacementCandidate findPlacementInfo(BlockPos targetPos) {
        if (!isAirBlock(targetPos)) {
            return null;
        }

        EnumFacing[] priorities = new EnumFacing[] {
            EnumFacing.DOWN,
            EnumFacing.NORTH,
            EnumFacing.SOUTH,
            EnumFacing.EAST,
            EnumFacing.WEST,
            EnumFacing.UP
        };
        for (EnumFacing face : priorities) {
            BlockPos neighbor = targetPos.offset(face);
            if (!isAttachableBlock(neighbor)) {
                continue;
            }
            return new PlacementCandidate(targetPos, neighbor, face.getOpposite(), 0.0D);
        }

        return null;
    }

    private PlacementCandidate makePlacementAgainst(BlockPos targetPos, BlockPos neighbor) {
        if (!isAttachableBlock(neighbor)) {
            return null;
        }

        int dx = targetPos.getX() - neighbor.getX();
        int dy = targetPos.getY() - neighbor.getY();
        int dz = targetPos.getZ() - neighbor.getZ();

        EnumFacing face;
        if (dx == 1) {
            face = EnumFacing.EAST;
        } else if (dx == -1) {
            face = EnumFacing.WEST;
        } else if (dz == 1) {
            face = EnumFacing.SOUTH;
        } else if (dz == -1) {
            face = EnumFacing.NORTH;
        } else if (dy == 1) {
            face = EnumFacing.UP;
        } else if (dy == -1) {
            face = EnumFacing.DOWN;
        } else {
            return null;
        }

        return new PlacementCandidate(targetPos, neighbor, face, 0.0D);
    }

    private List<BlockPos> calculateBridgePath(BlockPos from, BlockPos to) {
        List<BlockPos> path = new ArrayList<BlockPos>();
        path.add(from);

        int x = from.getX();
        int z = from.getZ();
        int y = from.getY();
        int targetX = to.getX();
        int targetZ = to.getZ();

        while (x != targetX || z != targetZ) {
            int dx = targetX - x;
            int dz = targetZ - z;
            if (Math.abs(dx) >= Math.abs(dz)) {
                x += dx > 0 ? 1 : -1;
            } else {
                z += dz > 0 ? 1 : -1;
            }
            path.add(new BlockPos(x, y, z));
        }

        return path;
    }

    private double predictYAfterTicks(EntityPlayerSP player, int ticks) {
        double y = player.posY;
        double velocityY = player.motionY;
        for (int i = 0; i < ticks; i++) {
            velocityY = (velocityY - 0.08D) * 0.98D;
            y += velocityY;
        }
        return y;
    }

    private int countAvailableBlocks(EntityPlayerSP player) {
        int total = 0;
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }

            Block block = ((ItemBlock) stack.getItem()).getBlock();
            if (block == null || !block.isFullCube()) {
                continue;
            }

            total += stack.stackSize;
        }
        return total;
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
        rotationHeldTicks = 0;
        ClientRotationHelper.get().clearRequestedRotations();
    }

    private void resetState() {
        blocksPlaced = 0;
        savedSlot = -1;
        moveFreezeTicks = 0;
        groundedClutchTicks = 0;
        clutching = false;
        returningToCamera = false;
        rotationHeldTicks = 0;
        bridgePath = null;
        bridgeIndex = 0;
        bridgeStartPlacement = null;
    }

    private boolean shouldLockMovement() {
        return isEnabled() && isPlayerReady() && (clutching || returningToCamera || moveFreezeTicks > 0);
    }

    private void applyImmediateLandingLock(EntityPlayerSP player) {
        if (player == null || player.movementInput == null) {
            return;
        }

        player.moveForward = 0.0F;
        player.moveStrafing = 0.0F;
        player.movementInput.moveForward = 0.0F;
        player.movementInput.moveStrafe = 0.0F;
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
        private final BlockPos targetPos;
        private final BlockPos neighbor;
        private final EnumFacing face;
        private final double score;

        private PlacementCandidate(BlockPos targetPos, BlockPos neighbor, EnumFacing face, double score) {
            this.targetPos = targetPos;
            this.neighbor = neighbor;
            this.face = face;
            this.score = score;
        }
    }
}
