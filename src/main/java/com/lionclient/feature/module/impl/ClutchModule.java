package com.lionclient.feature.module.impl;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.NumberSetting;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;
import org.lwjgl.input.Keyboard;

public final class ClutchModule extends Module {
    private final BooleanSetting enabledSetting = new BooleanSetting("Enabled", true);
    private final DecimalSetting knockbackThreshold = new DecimalSetting("KB Threshold", 0.1D, 1.5D, 0.1D, 0.4D);
    private final NumberSetting scanDepth = new NumberSetting("Scan Depth", 1, 16, 1, 8);
    private final BooleanSetting snapViewBack = new BooleanSetting("Snap View Back", true);
    private final BooleanSetting switchBackItem = new BooleanSetting("Switch Back Item", true);
    private final BooleanSetting requireSneaking = new BooleanSetting("Skip While Sneaking", false);

    private double prevMotionX = 0.0D;
    private double prevMotionZ = 0.0D;
    private boolean clutchArmed = false;
    private boolean hasPlaced = false;
    private int delayTicks = 0;

    public ClutchModule() {
        super("Clutch", "Places a block under you when you fall into the void.", Category.MOVEMENT, Keyboard.KEY_NONE);
        addSetting(enabledSetting);
        addSetting(knockbackThreshold);
        addSetting(scanDepth);
        addSetting(snapViewBack);
        addSetting(switchBackItem);
        addSetting(requireSneaking);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.thePlayer;
        if (player == null || minecraft.theWorld == null || minecraft.playerController == null) {
            resetMotion();
            return;
        }

        if (!enabledSetting.isEnabled()) {
            resetState();
            return;
        }

        if (player.capabilities.isCreativeMode || player.isInWater() || player.isInLava()) {
            resetState();
            return;
        }

        if (requireSneaking.isEnabled() && player.isSneaking()) {
            clutchArmed = false;
            delayTicks = 0;
            return;
        }

        double dvX = player.motionX - prevMotionX;
        double dvZ = player.motionZ - prevMotionZ;
        double dv = Math.sqrt(dvX * dvX + dvZ * dvZ);

        if (dv > knockbackThreshold.getValue() && !player.onGround) {
            clutchArmed = true;
            delayTicks = 2;
        }

        prevMotionX = player.motionX;
        prevMotionZ = player.motionZ;

        if (!player.onGround && !hasSolidBlockBelow(player, minecraft)) {
            clutchArmed = true;
        }

        if (player.onGround) {
            clutchArmed = false;
            hasPlaced = false;
            delayTicks = 0;
            return;
        }

        if (!clutchArmed || hasPlaced) {
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        int previousSlot = player.inventory.currentItem;
        int targetSlot = findBlockSlot(player);
        if (targetSlot == -1) {
            return;
        }

        BlockPos placePos = new BlockPos(player.posX, player.getEntityBoundingBox().minY - 1.0D, player.posZ);
        if (isSolidBlock(placePos)) {
            return;
        }

        PlacementTarget placementTarget = findPlacementTarget(placePos);
        if (placementTarget == null) {
            return;
        }

        float previousYaw = player.rotationYaw;
        float previousPitch = player.rotationPitch;

        player.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(
            player.rotationYaw,
            90.0F,
            player.onGround
        ));

        player.rotationYaw = previousYaw;
        player.rotationPitch = 90.0F;
        player.inventory.currentItem = targetSlot;

        boolean placed = minecraft.playerController.onPlayerRightClick(
            player,
            minecraft.theWorld,
            player.inventory.getStackInSlot(targetSlot),
            placementTarget.neighborPos,
            placementTarget.sideHit,
            placementTarget.hitVec
        );

        if (snapViewBack.isEnabled()) {
            player.rotationYaw = previousYaw;
            player.rotationPitch = previousPitch;
            player.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(
                previousYaw,
                previousPitch,
                player.onGround
            ));
        }

        if (switchBackItem.isEnabled()) {
            player.inventory.currentItem = previousSlot;
        }

        if (placed) {
            player.swingItem();
            hasPlaced = true;
        }
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    private boolean hasSolidBlockBelow(EntityPlayerSP player, Minecraft minecraft) {
        BlockPos feet = new BlockPos(
            (int) Math.floor(player.posX),
            (int) Math.floor(player.posY),
            (int) Math.floor(player.posZ)
        );
        for (int i = 1; i <= scanDepth.getValue(); i++) {
            IBlockState state = minecraft.theWorld.getBlockState(feet.down(i));
            if (state != null && state.getBlock() != null && state.getBlock().getMaterial() != Material.air) {
                return true;
            }
        }
        return false;
    }

    private int findBlockSlot(EntityPlayerSP player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }

            Block block = ((ItemBlock) stack.getItem()).getBlock();
            if (block != null && block.getMaterial() != Material.air) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSolidBlock(BlockPos pos) {
        IBlockState state = Minecraft.getMinecraft().theWorld.getBlockState(pos);
        Block block = state.getBlock();
        return block.getMaterial() != Material.air && block.canCollideCheck(state, false);
    }

    private PlacementTarget findPlacementTarget(BlockPos placePos) {
        EnumFacing[] faces = new EnumFacing[] {
            EnumFacing.DOWN,
            EnumFacing.NORTH,
            EnumFacing.SOUTH,
            EnumFacing.WEST,
            EnumFacing.EAST,
            EnumFacing.UP
        };

        for (EnumFacing face : faces) {
            BlockPos neighborPos = placePos.offset(face);
            IBlockState neighborState = Minecraft.getMinecraft().theWorld.getBlockState(neighborPos);
            Block neighborBlock = neighborState.getBlock();
            if (neighborBlock == null || !neighborBlock.canCollideCheck(neighborState, false)) {
                continue;
            }

            EnumFacing sideHit = face.getOpposite();
            Vec3 hitVec = new Vec3(
                neighborPos.getX() + 0.5D + sideHit.getFrontOffsetX() * 0.5D,
                neighborPos.getY() + 0.5D + sideHit.getFrontOffsetY() * 0.5D,
                neighborPos.getZ() + 0.5D + sideHit.getFrontOffsetZ() * 0.5D
            );
            return new PlacementTarget(neighborPos, sideHit, hitVec);
        }

        return null;
    }

    private void resetState() {
        clutchArmed = false;
        hasPlaced = false;
        delayTicks = 0;
        resetMotion();
    }

    private void resetMotion() {
        prevMotionX = 0.0D;
        prevMotionZ = 0.0D;
    }

    private static final class PlacementTarget {
        private final BlockPos neighborPos;
        private final EnumFacing sideHit;
        private final Vec3 hitVec;

        private PlacementTarget(BlockPos neighborPos, EnumFacing sideHit, Vec3 hitVec) {
            this.neighborPos = neighborPos;
            this.sideHit = sideHit;
            this.hitVec = hitVec;
        }
    }
}
