package org.hyzionstudios.mysticessentials.api.rtp;

/**
 * How a bare {@code /rtp} (no explicit world or profile) chooses its destination.
 * See the Teleportation module's {@code rtp.json} {@code randomTeleport} block.
 */
public enum RtpSelectionMode {
    /** Always send the player to the configured default world/profile, wherever they run {@code /rtp}. */
    DEFAULT_WORLD,
    /** Use the player's current world when that world has RTP enabled; otherwise fall through. */
    CURRENT_WORLD,
    /** The player's current world maps to a specific profile. */
    PER_WORLD_PROFILE,
    /** The highest-priority profile granted through the player's permissions is used. */
    PERMISSION_PROFILE
}
