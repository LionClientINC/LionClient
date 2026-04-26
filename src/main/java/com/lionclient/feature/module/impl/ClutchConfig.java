package com.lionclient.feature.module.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plain POJO configuration for the Clutch module.
 * All fields are public for direct access. No framework dependencies.
 */
public final class ClutchConfig {

    /** Activate clutch when falling into the void (posY < 1.0). */
    public boolean onVoid = true;

    /** Activate clutch when fall damage would be lethal. */
    public boolean onLethalFall = true;

    /** Activate clutch based on raw fall distance exceeding minFallBlocks. */
    public boolean onDistanceFall = false;

    /** Minimum fall distance in blocks to trigger when onDistanceFall is true. */
    public float minFallBlocks = 4.0f;

    /**
     * If true: use RotationManager (server-side only, no visual snap).
     * If false: also visually snap client pitch to 90 degrees.
     * NOTE: even with silentAim=false, motionX/Y/Z are never modified.
     */
    public boolean silentAim = false;

    /** Render available block count near crosshair during clutch. */
    public boolean showBlockCount = true;

    /** Restore original hotbar slot after clutch completes. */
    public boolean returnToSlot = true;

    /** Place blocks diagonally on repeat jumps for staircase building. */
    public boolean allowStaircaseUp = false;

    /** Abort clutch if gap needs more than this many blocks. */
    public int maxBlocks = 6;

    /** Block names that should never be used for clutching. */
    public List<String> blacklist = Arrays.asList("sand", "gravel", "tnt", "anvil");

    /** If non-empty, only these block names are allowed. Empty = allow all non-blacklisted. */
    public List<String> whitelist = new ArrayList<String>();
}
