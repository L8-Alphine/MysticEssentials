package org.hyzionstudios.mysticessentials.api.rtp;

/** Terminal outcome of a Random Teleport request. */
public enum RtpStatus {
    /** The player was teleported to a validated destination. */
    SUCCESS,
    /** Random teleport is disabled server-wide. */
    DISABLED,
    /** No profile could be resolved for the request. */
    NO_PROFILE,
    /** The resolved profile is disabled. */
    PROFILE_DISABLED,
    /** The destination world is unknown or not loaded. */
    WORLD_UNAVAILABLE,
    /** The player lacks permission for the profile/world/feature. */
    NO_PERMISSION,
    /** A cooldown is still active. */
    ON_COOLDOWN,
    /** A usage limit (hourly/daily) was reached. */
    LIMIT_REACHED,
    /** The player cannot afford the profile cost. */
    NOT_ENOUGH_MONEY,
    /** The player is in combat and combat blocks RTP. */
    IN_COMBAT,
    /** The search queue is full. */
    QUEUE_FULL,
    /** The player already has an active RTP request. */
    ALREADY_ACTIVE,
    /** No safe destination was found within the search budget. */
    NO_DESTINATION,
    /** The request was cancelled (see {@link RtpCancelReason}). */
    CANCELLED,
    /** An unexpected error occurred (the player is never charged for this). */
    FAILED
}
