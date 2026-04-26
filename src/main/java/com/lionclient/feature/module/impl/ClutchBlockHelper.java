package com.lionclient.feature.module.impl;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.BlockPos;

/**
 * Static helper class for the Clutch module.
 * Handles hotbar slot scanning, block counting, and placement packet sending.
 */
public final class ClutchBlockHelper {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private ClutchBlockHelper() {
    }

    /**
     * Finds the best hotbar slot containing a valid block for clutching.
     * Scans slots 0-8, skipping null stacks, non-block items, blacklisted blocks,
     * and blocks not on the whitelist (if the whitelist is non-empty).
     *
     * @param config the clutch configuration containing blacklist/whitelist
     * @return the slot index (0-8) of the best block, or -1 if none found
     */
    public static int findBestSlot(ClutchConfig config) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }

            String name = String.valueOf(Block.blockRegistry.getNameForObject(((ItemBlock) stack.getItem()).getBlock()));
            if (config.blacklist.contains(name)) {
                continue;
            }
            if (!config.whitelist.isEmpty() && !config.whitelist.contains(name)) {
                continue;
            }

            return slot;
        }
        return -1;
    }

    /**
     * Counts the total number of available blocks across all hotbar slots
     * that pass the same filter as {@link #findBestSlot(ClutchConfig)}.
     *
     * @param config the clutch configuration containing blacklist/whitelist
     * @return the total stack size of all valid block stacks
     */
    public static int countAvailableBlocks(ClutchConfig config) {
        int total = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }

            String name = String.valueOf(Block.blockRegistry.getNameForObject(((ItemBlock) stack.getItem()).getBlock()));
            if (config.blacklist.contains(name)) {
                continue;
            }
            if (!config.whitelist.isEmpty() && !config.whitelist.contains(name)) {
                continue;
            }

            total += stack.stackSize;
        }
        return total;
    }

    /**
     * Sends a block placement packet for the block directly beneath the player,
     * followed by a swing animation packet. Decrements the current item stack
     * client-side and removes it if the stack is depleted.
     */
    public static void placeBlockBeneath() {
        BlockPos target = new BlockPos(
                (int) Math.floor(mc.thePlayer.posX),
                (int) Math.floor(mc.thePlayer.posY) - 1,
                (int) Math.floor(mc.thePlayer.posZ)
        );

        mc.thePlayer.sendQueue.addToSendQueue(
                new C08PacketPlayerBlockPlacement(
                        target,
                        1,
                        mc.thePlayer.inventory.getCurrentItem(),
                        0.5f, 0.5f, 0.5f
                )
        );

        mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());

        ItemStack cur = mc.thePlayer.inventory.getCurrentItem();
        if (cur != null) {
            cur.stackSize--;
            if (cur.stackSize <= 0) {
                mc.thePlayer.inventory.setInventorySlotContents(
                        mc.thePlayer.inventory.currentItem, null
                );
            }
        }
    }
}
