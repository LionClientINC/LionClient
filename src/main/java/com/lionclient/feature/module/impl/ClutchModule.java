package com.lionclient.feature.module.impl;

import com.lionclient.combat.ClientRotationHelper;
import com.lionclient.event.ClientRotationEvent;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.util.BlockPos;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Clutch module — automatically places blocks beneath the player to prevent
 * lethal falls, void deaths, or distance-based falls. Uses ClientRotationHelper
 * via ClientRotationEvent for server-side rotations (same system as KillAura).
 * Never modifies motionX/Y/Z or movement input.
 */
public final class ClutchModule extends Module {

    private static final ClutchModule INSTANCE = new ClutchModule();

    /** Configuration POJO for all clutch settings. */
    public final ClutchConfig config = new ClutchConfig();

    private enum State {
        IDLE,
        ACTIVATING,
        PLACING
    }

    private final Minecraft mc = Minecraft.getMinecraft();

    private State state = State.IDLE;
    private int savedSlot = -1;
    private int ticksInState = 0;
    private boolean forgeRegistered = false;

    // Staircase tracking
    private BlockPos lastPlacedPos = null;
    private int ticksSinceLastClutch = 0;

    // Whether we are actively requesting a rotation this tick
    private boolean requestingRotation = false;

    private ClutchModule() {
        super("Clutch", "Automatically places blocks to save you from fatal falls.", Category.PLAYER, Keyboard.KEY_NONE);
    }

    /**
     * Returns the singleton instance of ClutchModule.
     *
     * @return the ClutchModule instance
     */
    public static ClutchModule getInstance() {
        return INSTANCE;
    }

    /**
     * Determines whether the clutch should activate based on current player
     * state and configuration. Checks airborne status, downward velocity,
     * and trigger conditions (void, lethal fall, distance fall).
     *
     * @return true if the clutch should activate this tick
     */
    private boolean shouldActivate() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        if (mc.thePlayer.onGround) {
            return false;
        }
        if (mc.thePlayer.motionY >= 0) {
            return false;
        }

        // Condition A: void
        boolean condVoid = config.onVoid && mc.thePlayer.posY < 1.0;

        // Condition B: lethal fall
        boolean condLethal = false;
        if (config.onLethalFall) {
            int featherFallingLevel = EnchantmentHelper.getEnchantmentLevel(
                    Enchantment.featherFalling.effectId,
                    mc.thePlayer.getEquipmentInSlot(1)
            );
            float effectiveFall = mc.thePlayer.fallDistance - 3.0f - featherFallingLevel * 3.0f;
            condLethal = effectiveFall >= mc.thePlayer.getHealth();
        }

        // Condition C: distance fall
        boolean condDistance = config.onDistanceFall && mc.thePlayer.fallDistance > config.minFallBlocks;

        return condVoid || condLethal || condDistance;
    }

    /**
     * Main tick handler — called by ModuleManager each client tick when enabled.
     * Manages the IDLE → ACTIVATING → PLACING state machine.
     *
     * @param event the client tick event
     */
    @Override
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        // Always increment staircase timer (cap at 100)
        if (ticksSinceLastClutch < 100) {
            ticksSinceLastClutch++;
        }

        switch (state) {
            case IDLE:
                tickIdle();
                break;
            case ACTIVATING:
                tickActivating();
                break;
            case PLACING:
                tickPlacing();
                break;
        }
    }

    private void tickIdle() {
        if (!shouldActivate()) {
            return;
        }
        int bestSlot = ClutchBlockHelper.findBestSlot(config);
        if (bestSlot == -1) {
            return;
        }

        savedSlot = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = bestSlot;

        // Flag that we need a server-side rotation — the actual rotation is
        // applied in onClientRotation via the ClientRotationEvent, which fires
        // from ClientRotationHelper.updateServerRotations() in the mixin.
        requestingRotation = true;

        // If silentAim is false, also set client pitch directly (camera only, NOT motion)
        if (!config.silentAim) {
            mc.thePlayer.rotationPitch = 90f;
        }

        ticksInState = 0;
        state = State.ACTIVATING;
    }

    private void tickActivating() {
        ticksInState++;

        // Wait 1 tick for rotation packet to be sent first
        if (ticksInState < 1) {
            return;
        }

        // Estimate blocks needed: rough = fallDistance / 1.5 + 1
        int needed = (int) (mc.thePlayer.fallDistance / 1.5f) + 1;
        if (needed > config.maxBlocks) {
            resetState();
            return;
        }

        // Handle staircase
        BlockPos target;
        if (config.allowStaircaseUp && lastPlacedPos != null && ticksSinceLastClutch < 5) {
            int dx = (int) Math.signum(mc.thePlayer.motionX);
            int dz = (int) Math.signum(mc.thePlayer.motionZ);
            target = new BlockPos(
                    lastPlacedPos.getX() + dx,
                    (int) Math.floor(mc.thePlayer.posY) - 1,
                    lastPlacedPos.getZ() + dz
            );
        } else {
            target = new BlockPos(
                    (int) Math.floor(mc.thePlayer.posX),
                    (int) Math.floor(mc.thePlayer.posY) - 1,
                    (int) Math.floor(mc.thePlayer.posZ)
            );
        }

        ClutchBlockHelper.placeBlockBeneath();
        lastPlacedPos = target;
        ticksSinceLastClutch = 0;
        ticksInState = 0;
        state = State.PLACING;
    }

    private void tickPlacing() {
        ticksInState++;

        // Check if block placed successfully
        BlockPos under = new BlockPos(
                (int) Math.floor(mc.thePlayer.posX),
                (int) Math.floor(mc.thePlayer.posY) - 1,
                (int) Math.floor(mc.thePlayer.posZ)
        );

        boolean solid = mc.theWorld.getBlockState(under).getBlock().getMaterial().isSolid();
        if (solid || mc.thePlayer.onGround || ticksInState > 10) {
            resetState();
        }
    }

    /**
     * Resets the clutch state machine back to IDLE.
     * Restores the original hotbar slot if configured, and stops
     * requesting server-side rotations.
     */
    private void resetState() {
        if (config.returnToSlot && savedSlot != -1) {
            mc.thePlayer.inventory.currentItem = savedSlot;
        }
        requestingRotation = false;
        ClientRotationHelper.get().clearRequestedRotations();
        savedSlot = -1;
        ticksInState = 0;
        state = State.IDLE;
    }

    /**
     * Handles the ClientRotationEvent to set server-side rotations
     * via ClientRotationHelper — the same mechanism KillAura uses.
     * Sets yaw to current player yaw and pitch to 90 (straight down).
     *
     * @param event the rotation event fired by ClientRotationHelper
     */
    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent event) {
        if (!isEnabled() || !requestingRotation) {
            return;
        }
        if (mc.thePlayer == null) {
            return;
        }

        // Server-side pitch 90 (straight down), keep current yaw
        event.yaw = Float.valueOf(mc.thePlayer.rotationYaw);
        event.pitch = Float.valueOf(90f);
    }

    @Override
    protected void onEnable() {
        registerForge();
    }

    @Override
    protected void onDisable() {
        unregisterForge();
        if (mc.thePlayer != null) {
            resetState();
        }
    }

    /**
     * Renders the available block count near the crosshair when
     * the module is active and showBlockCount is enabled.
     * Called by ModuleManager via the Module lifecycle.
     *
     * @param event the render overlay event
     */
    @Override
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (!config.showBlockCount || state == State.IDLE) {
            return;
        }

        int count = ClutchBlockHelper.countAvailableBlocks(config);
        ScaledResolution res = new ScaledResolution(mc);
        int x = res.getScaledWidth() / 2 + 10;
        int y = res.getScaledHeight() / 2 + 10;
        mc.fontRendererObj.drawStringWithShadow(String.valueOf(count), x, y, 0xFFFFFF);
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
}
