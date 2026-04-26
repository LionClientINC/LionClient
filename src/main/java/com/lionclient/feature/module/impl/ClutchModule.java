package com.lionclient.feature.module.impl;

import com.lionclient.combat.ClientRotationHelper;
import com.lionclient.event.ClientRotationEvent;
import com.lionclient.event.RunTickStartEvent;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockTNT;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

public final class ClutchModule extends Module {
    private static final ClutchModule INSTANCE = new ClutchModule();

    private final Minecraft mc = Minecraft.getMinecraft();

    private final EnumSetting<ActivationMode> activationMode = new EnumSetting<ActivationMode>(
        "Activation",
        ActivationMode.values(),
        ActivationMode.Lethal
    );
    private final DecimalSetting fallDistanceThreshold = new DecimalSetting("Fall Distance", 3.0D, 30.0D, 1.0D, 5.0D);
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", true);
    private final BooleanSetting resetAngle = new BooleanSetting("Reset Angle", true);
    private final BooleanSetting returnToSlot = new BooleanSetting("Return To Slot", true);
    private final BooleanSetting showBlockCount = new BooleanSetting("Show Block Count", false);
    private final NumberSetting clutchMoveDelay = new NumberSetting("Move Delay", 0, 10, 1, 2);
    private final NumberSetting maxBlocks = new NumberSetting("Max Blocks", 1, 20, 1, 5);
    private final BooleanSetting allowStaircase = new BooleanSetting("Allow Staircase", false);
    private final EnumSetting<BlockSelectionMode> blockSelectionMode = new EnumSetting<BlockSelectionMode>(
        "Block Selection",
        BlockSelectionMode.values(),
        BlockSelectionMode.Normal
    );

    private ClutchPhase phase = ClutchPhase.IDLE;
    private int originalSlot = -1;
    private float targetYaw;
    private float targetPitch;
    private int freezeTicks;
    private int blocksPlaced;
    private boolean spoofingRotation;
    private int restoreTicks;
    private PlacementInfo pendingPlacement;
    private List<BlockPos> bridgePath;
    private int bridgeIndex;
    private PlacementInfo edgeInfo;
    private boolean forgeRegistered;

    private ClutchModule() {
        super("Clutch", "Automatically places a block when falling.", Category.PLAYER, Keyboard.KEY_NONE);
        addSetting(activationMode);
        addSetting(fallDistanceThreshold);
        addSetting(silentAim);
        addSetting(resetAngle);
        addSetting(returnToSlot);
        addSetting(showBlockCount);
        addSetting(clutchMoveDelay);
        addSetting(maxBlocks);
        addSetting(allowStaircase);
        addSetting(blockSelectionMode);
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
        if (mc.thePlayer != null && spoofingRotation && !silentAim.isEnabled()) {
            mc.thePlayer.rotationYaw = targetYaw;
            mc.thePlayer.rotationPitch = targetPitch;
        }
        ClientRotationHelper.get().clearRequestedRotations();
        resetState();
    }

    @Override
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (!isPlayerReady() || mc.thePlayer.capabilities.isCreativeMode) {
            return;
        }

        if (freezeTicks > 0) {
            mc.thePlayer.motionX = 0.0D;
            mc.thePlayer.motionZ = 0.0D;
            freezeTicks--;
            if (freezeTicks <= 0) {
                resetState();
            }
            return;
        }

        if (phase == ClutchPhase.FREEZING) {
            return;
        }

        switch (phase) {
            case IDLE:
                handleIdle();
                break;
            case PLACING:
                handlePlacing();
                break;
            case BRIDGING:
                handleBridging();
                break;
            case RESTORING:
                handleRestoring();
                break;
            default:
                break;
        }
    }

    @SubscribeEvent
    public void onRunTickStart(RunTickStartEvent event) {
        if (pendingPlacement == null || !isPlayerReady() || mc.thePlayer.capabilities.isCreativeMode) {
            return;
        }

        PlacementInfo info = pendingPlacement;
        pendingPlacement = null;

        Block targetBlock = mc.theWorld.getBlockState(info.targetPos).getBlock();
        if (targetBlock != Blocks.air && !targetBlock.isReplaceable(mc.theWorld, info.targetPos)) {
            return;
        }

        Block neighborBlock = mc.theWorld.getBlockState(info.neighbor).getBlock();
        if (neighborBlock == Blocks.air || neighborBlock instanceof BlockLiquid || !neighborBlock.isCollidable()) {
            return;
        }

        Vec3 eyePos = new Vec3(
            mc.thePlayer.posX,
            mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
            mc.thePlayer.posZ
        );
        Vec3 placeVec = new Vec3(
            info.neighbor.getX() + 0.5D + info.face.getFrontOffsetX() * 0.5D,
            info.neighbor.getY() + 0.5D + info.face.getFrontOffsetY() * 0.5D,
            info.neighbor.getZ() + 0.5D + info.face.getFrontOffsetZ() * 0.5D
        );

        if (eyePos.distanceTo(placeVec) > 4.5D) {
            return;
        }

        double dx = placeVec.xCoord - mc.thePlayer.posX;
        double dz = placeVec.zCoord - mc.thePlayer.posZ;
        double dy = placeVec.yCoord - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dist = Math.sqrt(dx * dx + dz * dz);
        float freshYaw = MathHelper.wrapAngleTo180_float((float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F);
        float freshPitch = MathHelper.clamp_float((float) -(Math.atan2(dy, dist) * 180.0D / Math.PI), -90.0F, 90.0F);

        mc.thePlayer.sendQueue.addToSendQueue(
            new C03PacketPlayer.C05PacketPlayerLook(freshYaw, freshPitch, mc.thePlayer.onGround)
        );

        targetYaw = freshYaw;
        targetPitch = freshPitch;

        attemptBlockPlacement(info);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent event) {
        if (!isEnabled() || !spoofingRotation || !isPlayerReady()) {
            return;
        }

        event.yaw = Float.valueOf(targetYaw);
        event.pitch = Float.valueOf(targetPitch);
    }

    @Override
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (!showBlockCount.isEnabled() || !isPlayerReady() || mc.currentScreen != null) {
            return;
        }

        int count = countAvailableBlocks();
        if (count <= 0) {
            return;
        }

        ScaledResolution resolution = new ScaledResolution(mc);
        int rgb;
        if (count < 8) {
            rgb = Color.RED.getRGB();
        } else if (count < 16) {
            rgb = new Color(255, 165, 0).getRGB();
        } else if (count < 32) {
            rgb = Color.YELLOW.getRGB();
        } else {
            rgb = Color.GREEN.getRGB();
        }

        String text = count + " clutch blocks";
        int x = resolution.getScaledWidth() / 2 - mc.fontRendererObj.getStringWidth(text) / 2;
        int y = resolution.getScaledHeight() / 2 + 20;
        mc.fontRendererObj.drawString(text, (float) x, (float) y, rgb, false);
    }

    private void handleIdle() {
        if (mc.thePlayer.onGround
            || mc.thePlayer.capabilities.isFlying
            || mc.thePlayer.isInWater()
            || mc.thePlayer.isOnLadder()
            || mc.thePlayer.motionY >= 0.0D) {
            return;
        }

        int blockSlot = findBlockSlot();
        if (blockSlot == -1) {
            return;
        }

        if (shouldClutch()) {
            PlacementInfo info = findDirectPlacementTarget();
            if (info != null) {
                originalSlot = mc.thePlayer.inventory.currentItem;
                if (blockSlot != originalSlot) {
                    mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(blockSlot));
                    mc.thePlayer.inventory.currentItem = blockSlot;
                }

                placeAt(info);
                blocksPlaced++;

                if (allowStaircase.isEnabled() && blocksPlaced < maxBlocks.getValue() && shouldClutch()) {
                    phase = ClutchPhase.PLACING;
                } else {
                    phase = ClutchPhase.RESTORING;
                    restoreTicks = 1;
                }
                return;
            }
        }

        boolean dangerousFall = mc.thePlayer.fallDistance >= 0.5F || mc.thePlayer.posY < 5.0D;
        if (!dangerousFall || !needsBridging()) {
            return;
        }

        edgeInfo = findNearestEdge();
        if (edgeInfo == null) {
            return;
        }

        originalSlot = mc.thePlayer.inventory.currentItem;
        if (blockSlot != originalSlot) {
            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(blockSlot));
            mc.thePlayer.inventory.currentItem = blockSlot;
        }

        double futureX = mc.thePlayer.posX + mc.thePlayer.motionX;
        double futureZ = mc.thePlayer.posZ + mc.thePlayer.motionZ;
        int playerX = MathHelper.floor_double(futureX);
        int playerZ = MathHelper.floor_double(futureZ);

        int edgeX = edgeInfo.targetPos.getX();
        int edgeZ = edgeInfo.targetPos.getZ();
        int estimatedPathLength = Math.abs(edgeX - playerX) + Math.abs(edgeZ - playerZ);
        if (estimatedPathLength < 1) {
            estimatedPathLength = 1;
        }

        double predictedY = predictYAfterTicks(estimatedPathLength);
        int bridgeY = MathHelper.floor_double(predictedY) - 1;
        int edgeBlockY = edgeInfo.neighbor.getY();
        if (bridgeY > edgeBlockY) {
            bridgeY = edgeBlockY;
        }

        BlockPos playerColumn = new BlockPos(playerX, bridgeY, playerZ);
        BlockPos bridgeStart = new BlockPos(edgeX, bridgeY, edgeZ);
        bridgePath = calculateBridgePath(bridgeStart, playerColumn);

        double fracX = futureX - Math.floor(futureX);
        double fracZ = futureZ - Math.floor(futureZ);
        BlockPos last = bridgePath.get(bridgePath.size() - 1);

        if (fracX >= 0.7D) {
            bridgePath.add(new BlockPos(last.getX() + 1, bridgeY, last.getZ()));
        } else if (fracX <= 0.3D) {
            bridgePath.add(new BlockPos(last.getX() - 1, bridgeY, last.getZ()));
        }

        last = bridgePath.get(bridgePath.size() - 1);
        if (fracZ >= 0.7D) {
            bridgePath.add(new BlockPos(last.getX(), bridgeY, last.getZ() + 1));
        } else if (fracZ <= 0.3D) {
            bridgePath.add(new BlockPos(last.getX(), bridgeY, last.getZ() - 1));
        }

        PlacementInfo startInfo = findPlacementInfo(bridgeStart);
        if (startInfo != null) {
            edgeInfo = startInfo;
        } else {
            edgeInfo = new PlacementInfo(bridgeStart, edgeInfo.neighbor, edgeInfo.face);
        }

        int available = countAvailableBlocks();
        if (bridgePath.size() > available) {
            bridgePath = bridgePath.subList(0, available);
        }

        if (bridgePath.isEmpty()) {
            restoreSlot();
            return;
        }

        placeAt(edgeInfo);
        blocksPlaced++;
        bridgeIndex = 1;

        if (bridgeIndex < bridgePath.size()) {
            phase = ClutchPhase.BRIDGING;
        } else {
            phase = ClutchPhase.RESTORING;
            restoreTicks = 1;
        }
    }

    private void handlePlacing() {
        if (mc.thePlayer.onGround || blocksPlaced >= maxBlocks.getValue()) {
            phase = ClutchPhase.RESTORING;
            restoreTicks = 1;
            return;
        }

        PlacementInfo info = findDirectPlacementTarget();
        if (info != null) {
            placeAt(info);
            blocksPlaced++;

            if (allowStaircase.isEnabled()
                && blocksPlaced < maxBlocks.getValue()
                && shouldClutch()
                && !mc.thePlayer.onGround) {
                return;
            }
        }

        phase = ClutchPhase.RESTORING;
        restoreTicks = 1;
    }

    private void handleBridging() {
        if (mc.thePlayer.onGround || bridgePath == null || bridgeIndex >= bridgePath.size()) {
            phase = ClutchPhase.RESTORING;
            restoreTicks = 1;
            return;
        }

        BlockPos target = bridgePath.get(bridgeIndex);
        BlockPos previous = bridgePath.get(bridgeIndex - 1);
        PlacementInfo info = makePlacementAgainst(target, previous);
        if (info == null) {
            info = findPlacementInfo(target);
        }

        if (info == null) {
            phase = ClutchPhase.RESTORING;
            restoreTicks = 1;
            return;
        }

        Vec3 eyePos = new Vec3(
            mc.thePlayer.posX,
            mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
            mc.thePlayer.posZ
        );
        Vec3 placeVec = new Vec3(
            info.neighbor.getX() + 0.5D + info.face.getFrontOffsetX() * 0.5D,
            info.neighbor.getY() + 0.5D + info.face.getFrontOffsetY() * 0.5D,
            info.neighbor.getZ() + 0.5D + info.face.getFrontOffsetZ() * 0.5D
        );
        if (eyePos.distanceTo(placeVec) > 4.5D) {
            phase = ClutchPhase.RESTORING;
            restoreTicks = 1;
            return;
        }

        placeAt(info);
        blocksPlaced++;
        bridgeIndex++;

        if (bridgeIndex >= bridgePath.size() || mc.thePlayer.onGround) {
            phase = ClutchPhase.RESTORING;
            restoreTicks = 1;
        }
    }

    private void handleRestoring() {
        restoreTicks--;
        if (restoreTicks > 0) {
            return;
        }

        spoofingRotation = false;
        restoreSlot();

        int delay = clutchMoveDelay.getValue();
        if (delay > 0) {
            freezeTicks = delay;
            phase = ClutchPhase.FREEZING;
        } else {
            resetState();
        }
    }

    private void placeAt(PlacementInfo info) {
        computeTargetRotation(info);
        targetYaw = MathHelper.wrapAngleTo180_float(targetYaw);
        spoofingRotation = true;

        if (!silentAim.isEnabled()) {
            mc.thePlayer.rotationYaw = targetYaw;
            mc.thePlayer.rotationPitch = targetPitch;
        }

        pendingPlacement = info;
    }

    private boolean shouldClutch() {
        if (mc.thePlayer.onGround
            || mc.thePlayer.capabilities.isFlying
            || mc.thePlayer.isInWater()
            || mc.thePlayer.isOnLadder()
            || mc.thePlayer.motionY >= 0.0D) {
            return false;
        }

        switch (activationMode.getValue()) {
            case Void:
                return mc.thePlayer.posY < 0.0D;
            case Lethal:
                float effectiveFallDistance = mc.thePlayer.fallDistance + (float) (-mc.thePlayer.motionY * 2.0D);
                float reductionFromJumpBoost = 0.0F;
                PotionEffect jumpBoost = mc.thePlayer.getActivePotionEffect(Potion.jump);
                if (jumpBoost != null) {
                    reductionFromJumpBoost = jumpBoost.getAmplifier() + 1;
                }
                float predictedDamage = effectiveFallDistance - 3.0F - reductionFromJumpBoost;
                return predictedDamage >= mc.thePlayer.getHealth();
            case Distance:
                return mc.thePlayer.fallDistance >= fallDistanceThreshold.getValue();
            default:
                return false;
        }
    }

    private boolean needsBridging() {
        int playerX = MathHelper.floor_double(mc.thePlayer.posX);
        int playerZ = MathHelper.floor_double(mc.thePlayer.posZ);
        int playerY = MathHelper.floor_double(mc.thePlayer.posY);
        if (playerY < 5) {
            return true;
        }

        for (int dy = -1; dy >= -3; dy--) {
            Block block = mc.theWorld.getBlockState(new BlockPos(playerX, playerY + dy, playerZ)).getBlock();
            if (block != Blocks.air && !(block instanceof BlockLiquid) && block.isCollidable()) {
                return false;
            }
        }
        return true;
    }

    private double predictYAfterTicks(int ticks) {
        double y = mc.thePlayer.posY;
        double velocityY = mc.thePlayer.motionY;
        for (int i = 0; i < ticks; i++) {
            velocityY = (velocityY - 0.08D) * 0.98D;
            y += velocityY;
        }
        return y;
    }

    private int findBlockSlot() {
        int bestSlot = -1;
        int bestCount = 0;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }

            Block block = ((ItemBlock) stack.getItem()).getBlock();
            if (!isBlockValid(block)) {
                continue;
            }

            if (stack.stackSize > bestCount) {
                bestCount = stack.stackSize;
                bestSlot = slot;
            }
        }

        return bestSlot;
    }

    private int countAvailableBlocks() {
        int total = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }

            Block block = ((ItemBlock) stack.getItem()).getBlock();
            if (!isBlockValid(block)) {
                continue;
            }

            total += stack.stackSize;
        }
        return total;
    }

    private boolean isBlockValid(Block block) {
        if (block == null || block instanceof BlockAir) {
            return false;
        }
        if (block instanceof BlockTNT || block instanceof BlockFalling || block instanceof BlockLiquid) {
            return false;
        }
        if (!block.isFullCube() || !block.isCollidable()) {
            return false;
        }

        switch (blockSelectionMode.getValue()) {
            case Blacklist:
                return true;
            case Whitelist:
                return block == Blocks.cobblestone
                    || block == Blocks.stone
                    || block == Blocks.dirt
                    || block == Blocks.planks
                    || block == Blocks.sandstone
                    || block == Blocks.netherrack
                    || block == Blocks.end_stone
                    || block == Blocks.obsidian
                    || block == Blocks.wool
                    || block == Blocks.stained_hardened_clay
                    || block == Blocks.hardened_clay;
            case Normal:
            default:
                return true;
        }
    }

    private PlacementInfo findDirectPlacementTarget() {
        int playerX = MathHelper.floor_double(mc.thePlayer.posX);
        int playerZ = MathHelper.floor_double(mc.thePlayer.posZ);
        int playerY = MathHelper.floor_double(mc.thePlayer.posY);
        Vec3 eyePos = new Vec3(
            mc.thePlayer.posX,
            mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
            mc.thePlayer.posZ
        );

        for (int dy = -1; dy >= -4; dy--) {
            int targetY = playerY + dy;
            int[][] offsets = new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};

            for (int[] offset : offsets) {
                BlockPos targetPos = new BlockPos(playerX + offset[0], targetY, playerZ + offset[1]);
                PlacementInfo info = findPlacementInfo(targetPos);
                if (info == null) {
                    continue;
                }

                Vec3 placeVec = new Vec3(
                    info.neighbor.getX() + 0.5D + info.face.getFrontOffsetX() * 0.5D,
                    info.neighbor.getY() + 0.5D + info.face.getFrontOffsetY() * 0.5D,
                    info.neighbor.getZ() + 0.5D + info.face.getFrontOffsetZ() * 0.5D
                );
                if (eyePos.distanceTo(placeVec) <= 4.5D) {
                    return info;
                }
            }
        }

        return null;
    }

    private PlacementInfo findNearestEdge() {
        int playerX = MathHelper.floor_double(mc.thePlayer.posX);
        int playerZ = MathHelper.floor_double(mc.thePlayer.posZ);
        int playerY = MathHelper.floor_double(mc.thePlayer.posY);
        Vec3 eyePos = new Vec3(
            mc.thePlayer.posX,
            mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
            mc.thePlayer.posZ
        );

        PlacementInfo best = null;
        double bestScore = Double.MAX_VALUE;
        int range = 4;

        for (int dy = -4; dy <= 1; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos solidPos = new BlockPos(playerX + dx, playerY + dy, playerZ + dz);
                    Block block = mc.theWorld.getBlockState(solidPos).getBlock();
                    if (block instanceof BlockAir || block instanceof BlockLiquid || !block.isCollidable()) {
                        continue;
                    }

                    for (EnumFacing face : EnumFacing.values()) {
                        BlockPos airPos = solidPos.offset(face);
                        Block airBlock = mc.theWorld.getBlockState(airPos).getBlock();
                        if (airBlock != Blocks.air && !airBlock.isReplaceable(mc.theWorld, airPos)) {
                            continue;
                        }

                        Vec3 placeVec = new Vec3(
                            solidPos.getX() + 0.5D + face.getFrontOffsetX() * 0.5D,
                            solidPos.getY() + 0.5D + face.getFrontOffsetY() * 0.5D,
                            solidPos.getZ() + 0.5D + face.getFrontOffsetZ() * 0.5D
                        );
                        if (eyePos.distanceTo(placeVec) > 4.5D) {
                            continue;
                        }

                        double horizontalDistance = Math.sqrt(
                            (airPos.getX() + 0.5D - mc.thePlayer.posX) * (airPos.getX() + 0.5D - mc.thePlayer.posX)
                                + (airPos.getZ() + 0.5D - mc.thePlayer.posZ) * (airPos.getZ() + 0.5D - mc.thePlayer.posZ)
                        );
                        double verticalDistance = Math.abs(airPos.getY() - (playerY - 1));
                        double score = horizontalDistance + verticalDistance * 3.0D;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new PlacementInfo(airPos, solidPos, face);
                        }
                    }
                }
            }
        }

        return best;
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

    private PlacementInfo makePlacementAgainst(BlockPos target, BlockPos neighbor) {
        Block block = mc.theWorld.getBlockState(neighbor).getBlock();
        if (block instanceof BlockAir || block instanceof BlockLiquid || !block.isCollidable()) {
            return null;
        }

        int dx = target.getX() - neighbor.getX();
        int dy = target.getY() - neighbor.getY();
        int dz = target.getZ() - neighbor.getZ();

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
        } else {
            face = EnumFacing.DOWN;
        }

        return new PlacementInfo(target, neighbor, face);
    }

    private PlacementInfo findPlacementInfo(BlockPos targetPos) {
        Block targetBlock = mc.theWorld.getBlockState(targetPos).getBlock();
        if (targetBlock != Blocks.air && !targetBlock.isReplaceable(mc.theWorld, targetPos)) {
            return null;
        }

        EnumFacing[] facePriority = new EnumFacing[] {
            EnumFacing.DOWN,
            EnumFacing.NORTH,
            EnumFacing.SOUTH,
            EnumFacing.EAST,
            EnumFacing.WEST,
            EnumFacing.UP
        };

        for (EnumFacing face : facePriority) {
            BlockPos neighbor = targetPos.offset(face);
            Block neighborBlock = mc.theWorld.getBlockState(neighbor).getBlock();
            if (neighborBlock == Blocks.air || neighborBlock instanceof BlockLiquid || !neighborBlock.isCollidable()) {
                continue;
            }
            return new PlacementInfo(targetPos, neighbor, face.getOpposite());
        }

        return null;
    }

    private void computeTargetRotation(PlacementInfo info) {
        double targetX = info.neighbor.getX() + 0.5D + info.face.getFrontOffsetX() * 0.5D;
        double targetY = info.neighbor.getY() + 0.5D + info.face.getFrontOffsetY() * 0.5D;
        double targetZ = info.neighbor.getZ() + 0.5D + info.face.getFrontOffsetZ() * 0.5D;
        double dx = targetX - mc.thePlayer.posX;
        double dz = targetZ - mc.thePlayer.posZ;
        double dy = targetY - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dist = Math.sqrt(dx * dx + dz * dz);

        targetYaw = MathHelper.wrapAngleTo180_float((float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F);
        targetPitch = MathHelper.clamp_float((float) -(Math.atan2(dy, dist) * 180.0D / Math.PI), -90.0F, 90.0F);
    }

    private boolean attemptBlockPlacement(PlacementInfo info) {
        if (mc.playerController == null) {
            return false;
        }

        ItemStack heldItem = mc.thePlayer.getHeldItem();
        if (heldItem == null || !(heldItem.getItem() instanceof ItemBlock)) {
            return false;
        }

        Vec3 placeVec = new Vec3(
            info.neighbor.getX() + 0.5D + info.face.getFrontOffsetX() * 0.5D,
            info.neighbor.getY() + 0.5D + info.face.getFrontOffsetY() * 0.5D,
            info.neighbor.getZ() + 0.5D + info.face.getFrontOffsetZ() * 0.5D
        );

        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, heldItem, info.neighbor, info.face, placeVec)) {
            mc.thePlayer.swingItem();
            return true;
        }

        return false;
    }

    private void restoreSlot() {
        if (returnToSlot.isEnabled() && originalSlot != -1 && originalSlot != mc.thePlayer.inventory.currentItem) {
            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(originalSlot));
            mc.thePlayer.inventory.currentItem = originalSlot;
        }
    }

    private void resetState() {
        phase = ClutchPhase.IDLE;
        originalSlot = -1;
        freezeTicks = 0;
        blocksPlaced = 0;
        spoofingRotation = false;
        restoreTicks = 0;
        pendingPlacement = null;
        bridgePath = null;
        bridgeIndex = 0;
        edgeInfo = null;
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

    private enum ClutchPhase {
        IDLE,
        PLACING,
        BRIDGING,
        RESTORING,
        FREEZING
    }

    public enum ActivationMode {
        Void,
        Lethal,
        Distance
    }

    public enum BlockSelectionMode {
        Normal,
        Blacklist,
        Whitelist
    }

    private static final class PlacementInfo {
        private final BlockPos targetPos;
        private final BlockPos neighbor;
        private final EnumFacing face;

        private PlacementInfo(BlockPos targetPos, BlockPos neighbor, EnumFacing face) {
            this.targetPos = targetPos;
            this.neighbor = neighbor;
            this.face = face;
        }
    }
}
