package com.lionclient.feature.module.impl;

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
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.input.Keyboard;

public final class ClutchModule extends Module {
    private final EnumSetting<ActivationMode> activationMode =
        new EnumSetting<ActivationMode>("Activation", ActivationMode.values(), ActivationMode.LETHAL);
    private final DecimalSetting fallDistanceThreshold = new DecimalSetting("Fall Distance", 3.0D, 30.0D, 1.0D, 5.0D);
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", true);
    private final BooleanSetting resetAngle = new BooleanSetting("Reset Angle", true);
    private final BooleanSetting returnToSlot = new BooleanSetting("Return To Slot", true);
    private final BooleanSetting showBlockCount = new BooleanSetting("Show Blocks", false);
    private final NumberSetting clutchMoveDelay = new NumberSetting("Move Delay", 0, 10, 1, 2);
    private final NumberSetting maxBlocks = new NumberSetting("Max Blocks", 1, 20, 1, 5);
    private final BooleanSetting allowStaircase = new BooleanSetting("Allow Staircase", false);
    private final EnumSetting<BlockSelectionMode> blockSelectionMode =
        new EnumSetting<BlockSelectionMode>("Block Selection", BlockSelectionMode.values(), BlockSelectionMode.NORMAL);

    private ClutchPhase phase = ClutchPhase.IDLE;
    private int originalSlot = -1;
    private float targetYaw;
    private float targetPitch;
    private int freezeTicks = 0;
    private int blocksPlaced = 0;
    private List<BlockPos> bridgePath = null;
    private int bridgeIndex = 0;
    private PlacementInfo edgeInfo = null;

    public ClutchModule() {
        super("Clutch", "Automatically places blocks to save falls and bridge to safety.", Category.MOVEMENT, Keyboard.KEY_NONE);
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

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        restoreAngles();
        restoreSlot();
        resetState();
    }

    @Override
    public void onClientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || mc.playerController == null) {
            resetState();
            return;
        }

        if (freezeTicks > 0) {
            player.motionX = 0.0D;
            player.motionZ = 0.0D;
            freezeTicks--;
            if (freezeTicks == 0) {
                phase = ClutchPhase.IDLE;
            }
        }

        if (player.capabilities.isCreativeMode || player.capabilities.isFlying || player.isInWater() || player.isOnLadder()) {
            if (player.onGround) {
                restoreSlot();
                restoreAngles();
                resetState();
            }
            return;
        }

        if (player.onGround) {
            restoreSlot();
            restoreAngles();
            resetState();
            return;
        }

        switch (phase) {
            case IDLE:
                handleIdle(mc, player);
                break;
            case PLACING:
                handlePlacing(mc, player);
                break;
            case BRIDGING:
                handleBridging(mc, player);
                break;
            case RESTORING:
                handleRestoring();
                break;
            case FREEZING:
                break;
            default:
                break;
        }
    }

    @Override
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!showBlockCount.isEnabled() || mc.thePlayer == null || mc.currentScreen != null) {
            return;
        }

        int count = countAvailableBlocks();
        if (count <= 0) {
            return;
        }

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
        int x = event.resolution.getScaledWidth() / 2 - mc.fontRendererObj.getStringWidth(text) / 2;
        int y = event.resolution.getScaledHeight() / 2 + 20;
        mc.fontRendererObj.drawStringWithShadow(text, x, y, rgb);
    }

    private void handleIdle(Minecraft mc, EntityPlayerSP player) {
        if (player.motionY >= 0.0D) {
            return;
        }

        int blockSlot = findBlockSlot();
        if (blockSlot == -1) {
            return;
        }

        if (shouldClutch(mc, player)) {
            PlacementInfo info = findDirectPlacementTarget(mc, player);
            if (info != null) {
                prepareSlot(blockSlot);
                if (placeAt(mc, player, info)) {
                    blocksPlaced++;
                    if (allowStaircase.isEnabled() && blocksPlaced < maxBlocks.getValue() && shouldClutch(mc, player)) {
                        phase = ClutchPhase.PLACING;
                    } else {
                        phase = ClutchPhase.RESTORING;
                    }
                }
                return;
            }
        }

        if (!needsBridging(mc, player)) {
            return;
        }

        edgeInfo = findNearestEdge(mc, player);
        if (edgeInfo == null) {
            return;
        }

        prepareSlot(blockSlot);

        double futureX = player.posX + player.motionX;
        double futureZ = player.posZ + player.motionZ;
        int playerX = MathHelper.floor_double(futureX);
        int playerZ = MathHelper.floor_double(futureZ);
        int edgeX = edgeInfo.targetPos.getX();
        int edgeZ = edgeInfo.targetPos.getZ();
        int estimatedLength = Math.abs(edgeX - playerX) + Math.abs(edgeZ - playerZ);
        if (estimatedLength < 1) {
            estimatedLength = 1;
        }

        double predictedY = predictYAfterTicks(player, estimatedLength);
        int bridgeY = MathHelper.floor_double(predictedY) - 1;
        int edgeBlockY = edgeInfo.neighbor.getY();
        if (bridgeY > edgeBlockY) {
            bridgeY = edgeBlockY;
        }

        BlockPos playerColumn = new BlockPos(playerX, bridgeY, playerZ);
        BlockPos bridgeStart = new BlockPos(edgeX, bridgeY, edgeZ);
        bridgePath = calculateBridgePath(bridgeStart, playerColumn);

        extendBridgeForHitbox(bridgePath, futureX, futureZ, bridgeY);

        PlacementInfo startInfo = findPlacementInfo(mc, bridgeStart);
        if (startInfo != null) {
            edgeInfo = startInfo;
        } else {
            edgeInfo = new PlacementInfo(bridgeStart, edgeInfo.neighbor, edgeInfo.face);
        }

        int available = countAvailableBlocks();
        if (bridgePath.size() > available) {
            bridgePath = new ArrayList<BlockPos>(bridgePath.subList(0, available));
        }

        if (bridgePath.isEmpty()) {
            phase = ClutchPhase.RESTORING;
            return;
        }

        if (placeAt(mc, player, edgeInfo)) {
            blocksPlaced++;
            bridgeIndex = 1;
            if (bridgeIndex < bridgePath.size()) {
                phase = ClutchPhase.BRIDGING;
            } else {
                phase = ClutchPhase.RESTORING;
            }
        }
    }

    private void handlePlacing(Minecraft mc, EntityPlayerSP player) {
        if (player.onGround || blocksPlaced >= maxBlocks.getValue()) {
            phase = ClutchPhase.RESTORING;
            return;
        }

        PlacementInfo info = findDirectPlacementTarget(mc, player);
        if (info != null && placeAt(mc, player, info)) {
            blocksPlaced++;
            if (allowStaircase.isEnabled() && blocksPlaced < maxBlocks.getValue() && shouldClutch(mc, player) && !player.onGround) {
                return;
            }
        }

        phase = ClutchPhase.RESTORING;
    }

    private void handleBridging(Minecraft mc, EntityPlayerSP player) {
        if (player.onGround || bridgePath == null || bridgeIndex >= bridgePath.size()) {
            phase = ClutchPhase.RESTORING;
            return;
        }

        BlockPos target = bridgePath.get(bridgeIndex);
        BlockPos previous = bridgePath.get(bridgeIndex - 1);
        PlacementInfo info = makePlacementAgainst(mc, target, previous);
        if (info == null) {
            info = findPlacementInfo(mc, target);
        }
        if (info == null) {
            phase = ClutchPhase.RESTORING;
            return;
        }

        if (!isPlacementInReach(player, info)) {
            phase = ClutchPhase.RESTORING;
            return;
        }

        if (placeAt(mc, player, info)) {
            blocksPlaced++;
            bridgeIndex++;
            if (bridgeIndex >= bridgePath.size() || blocksPlaced >= maxBlocks.getValue()) {
                phase = ClutchPhase.RESTORING;
            }
            if (clutchMoveDelay.getValue() > 0) {
                freezeTicks = clutchMoveDelay.getValue();
                phase = ClutchPhase.FREEZING;
            }
            return;
        }

        phase = ClutchPhase.RESTORING;
    }

    private void handleRestoring() {
        restoreAngles();
        restoreSlot();
        resetState();
    }

    private boolean shouldClutch(Minecraft mc, EntityPlayerSP player) {
        if (player.onGround || player.capabilities.isFlying || player.isInWater() || player.isOnLadder()) {
            return false;
        }

        if (player.motionY >= 0.0D) {
            return false;
        }

        switch (activationMode.getValue()) {
            case VOID:
                return player.posY < 0.0D;
            case LETHAL:
                float effectiveFallDistance = player.fallDistance + (float) (-player.motionY * 2.0D);
                float predictedDamage = effectiveFallDistance - 3.0F;
                return predictedDamage >= player.getHealth();
            case DISTANCE:
                return player.fallDistance >= fallDistanceThreshold.getValue();
            default:
                return false;
        }
    }

    private boolean needsBridging(Minecraft mc, EntityPlayerSP player) {
        int px = MathHelper.floor_double(player.posX);
        int pz = MathHelper.floor_double(player.posZ);
        int py = MathHelper.floor_double(player.posY);
        if (py < 5) {
            return true;
        }

        for (int dy = -1; dy >= -3; dy--) {
            Block block = mc.theWorld.getBlockState(new BlockPos(px, py + dy, pz)).getBlock();
            if (block != Blocks.air && !(block instanceof BlockLiquid) && block.isCollidable()) {
                return false;
            }
        }
        return true;
    }

    private double predictYAfterTicks(EntityPlayerSP player, int ticks) {
        double y = player.posY;
        double vy = player.motionY;
        for (int i = 0; i < ticks; i++) {
            vy = (vy - 0.08D) * 0.98D;
            y += vy;
        }
        return y;
    }

    private int findBlockSlot() {
        Minecraft mc = Minecraft.getMinecraft();
        int bestSlot = -1;
        int bestCount = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }

            Block block = ((ItemBlock) stack.getItem()).getBlock();
            if (!isBlockValid(block)) {
                continue;
            }

            if (stack.stackSize > bestCount) {
                bestCount = stack.stackSize;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private int countAvailableBlocks() {
        Minecraft mc = Minecraft.getMinecraft();
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
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
        if (block == null || block instanceof BlockAir || block instanceof BlockTNT || block instanceof BlockFalling || block instanceof BlockLiquid) {
            return false;
        }
        if (!block.isFullCube() || !block.isCollidable()) {
            return false;
        }

        switch (blockSelectionMode.getValue()) {
            case WHITELIST:
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
            case BLACKLIST:
            case NORMAL:
            default:
                return true;
        }
    }

    private PlacementInfo findDirectPlacementTarget(Minecraft mc, EntityPlayerSP player) {
        int playerX = MathHelper.floor_double(player.posX);
        int playerZ = MathHelper.floor_double(player.posZ);
        int playerY = MathHelper.floor_double(player.posY);
        Vec3 eyePos = getEyePos(player);

        for (int dy = -1; dy >= -4; dy--) {
            int targetY = playerY + dy;
            int[][] offsets = new int[][] { {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
            for (int[] offset : offsets) {
                BlockPos targetPos = new BlockPos(playerX + offset[0], targetY, playerZ + offset[1]);
                PlacementInfo info = findPlacementInfo(mc, targetPos);
                if (info == null) {
                    continue;
                }
                if (getEyePos(player).distanceTo(getPlaceVec(info)) <= 4.5D) {
                    return info;
                }
            }
        }

        return null;
    }

    private PlacementInfo findNearestEdge(Minecraft mc, EntityPlayerSP player) {
        int px = MathHelper.floor_double(player.posX);
        int pz = MathHelper.floor_double(player.posZ);
        int py = MathHelper.floor_double(player.posY);
        Vec3 eyePos = getEyePos(player);
        PlacementInfo best = null;
        double bestScore = Double.MAX_VALUE;

        int range = 4;
        for (int dy = -4; dy <= 1; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos solidPos = new BlockPos(px + dx, py + dy, pz + dz);
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
                            (airPos.getX() + 0.5D - player.posX) * (airPos.getX() + 0.5D - player.posX)
                                + (airPos.getZ() + 0.5D - player.posZ) * (airPos.getZ() + 0.5D - player.posZ)
                        );
                        double verticalDistance = Math.abs(airPos.getY() - (py - 1));
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

    private void extendBridgeForHitbox(List<BlockPos> path, double futureX, double futureZ, int bridgeY) {
        if (path.isEmpty()) {
            return;
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
    }

    private PlacementInfo makePlacementAgainst(Minecraft mc, BlockPos target, BlockPos neighbor) {
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

    private PlacementInfo findPlacementInfo(Minecraft mc, BlockPos targetPos) {
        Block targetBlock = mc.theWorld.getBlockState(targetPos).getBlock();
        if (targetBlock != Blocks.air && !targetBlock.isReplaceable(mc.theWorld, targetPos)) {
            return null;
        }

        EnumFacing[] facePriority = new EnumFacing[] {
            EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST, EnumFacing.UP
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

    private boolean placeAt(Minecraft mc, EntityPlayerSP player, PlacementInfo info) {
        computeTargetRotation(player, info);

        float previousYaw = player.rotationYaw;
        float previousPitch = player.rotationPitch;
        player.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(targetYaw, targetPitch, player.onGround));
        if (!silentAim.isEnabled()) {
            player.rotationYaw = targetYaw;
            player.rotationPitch = targetPitch;
        }

        boolean placed = mc.playerController.onPlayerRightClick(
            player,
            mc.theWorld,
            player.getHeldItem(),
            info.neighbor,
            info.face,
            getPlaceVec(info)
        );

        if (placed) {
            player.swingItem();
        }

        if (resetAngle.isEnabled()) {
            player.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(previousYaw, previousPitch, player.onGround));
            if (!silentAim.isEnabled()) {
                player.rotationYaw = previousYaw;
                player.rotationPitch = previousPitch;
            }
        }

        return placed;
    }

    private void prepareSlot(int blockSlot) {
        Minecraft mc = Minecraft.getMinecraft();
        if (originalSlot == -1) {
            originalSlot = mc.thePlayer.inventory.currentItem;
        }
        if (mc.thePlayer.inventory.currentItem != blockSlot) {
            mc.thePlayer.inventory.currentItem = blockSlot;
        }
    }

    private void restoreSlot() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!returnToSlot.isEnabled() || mc.thePlayer == null || originalSlot == -1) {
            originalSlot = -1;
            return;
        }

        mc.thePlayer.inventory.currentItem = originalSlot;
        originalSlot = -1;
    }

    private void restoreAngles() {
        if (!resetAngle.isEnabled()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, mc.thePlayer.onGround));
        }
    }

    private boolean isPlacementInReach(EntityPlayerSP player, PlacementInfo info) {
        return getEyePos(player).distanceTo(getPlaceVec(info)) <= 4.5D;
    }

    private Vec3 getEyePos(EntityPlayerSP player) {
        return new Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ);
    }

    private Vec3 getPlaceVec(PlacementInfo info) {
        return new Vec3(
            info.neighbor.getX() + 0.5D + info.face.getFrontOffsetX() * 0.5D,
            info.neighbor.getY() + 0.5D + info.face.getFrontOffsetY() * 0.5D,
            info.neighbor.getZ() + 0.5D + info.face.getFrontOffsetZ() * 0.5D
        );
    }

    private void computeTargetRotation(EntityPlayerSP player, PlacementInfo info) {
        Vec3 placeVec = getPlaceVec(info);
        double dx = placeVec.xCoord - player.posX;
        double dz = placeVec.zCoord - player.posZ;
        double dy = placeVec.yCoord - (player.posY + player.getEyeHeight());
        double dist = Math.sqrt(dx * dx + dz * dz);
        targetYaw = MathHelper.wrapAngleTo180_float((float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F);
        targetPitch = MathHelper.clamp_float((float) -(Math.atan2(dy, dist) * 180.0D / Math.PI), -90.0F, 90.0F);
    }

    private void resetState() {
        phase = ClutchPhase.IDLE;
        originalSlot = -1;
        targetYaw = 0.0F;
        targetPitch = 0.0F;
        freezeTicks = 0;
        blocksPlaced = 0;
        bridgePath = null;
        bridgeIndex = 0;
        edgeInfo = null;
    }

    private enum ActivationMode {
        VOID,
        LETHAL,
        DISTANCE;

        @Override
        public String toString() {
            switch (this) {
                case VOID:
                    return "Void";
                case LETHAL:
                    return "Lethal";
                case DISTANCE:
                    return "Distance";
                default:
                    return name();
            }
        }
    }

    private enum BlockSelectionMode {
        NORMAL,
        BLACKLIST,
        WHITELIST;

        @Override
        public String toString() {
            switch (this) {
                case NORMAL:
                    return "Normal";
                case BLACKLIST:
                    return "Blacklist";
                case WHITELIST:
                    return "Whitelist";
                default:
                    return name();
            }
        }
    }

    private enum ClutchPhase {
        IDLE,
        PLACING,
        BRIDGING,
        RESTORING,
        FREEZING
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
