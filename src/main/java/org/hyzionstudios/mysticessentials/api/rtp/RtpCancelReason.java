package org.hyzionstudios.mysticessentials.api.rtp;

/** Why an in-progress Random Teleport (warmup, queued search, or pending move) was cancelled. */
public enum RtpCancelReason {
    MOVEMENT,
    DAMAGE_TAKEN,
    DAMAGE_DEALT,
    COMBAT,
    WORLD_CHANGE,
    MOUNT,
    VEHICLE,
    LOGOUT,
    DEATH,
    COMMAND,
    PERMISSION_LOST,
    ADMIN,
    SHUTDOWN,
    TIMEOUT
}
