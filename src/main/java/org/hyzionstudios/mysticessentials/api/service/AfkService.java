package org.hyzionstudios.mysticessentials.api.service;

import java.util.UUID;

/** Idle-state tracking: manual and automatic AFK, plus the AFK Rewards submodule. */
public interface AfkService {

    boolean isAfk(UUID player);

    /** Sets manual AFK state; {@code reason} may be {@code null}. */
    void setAfk(UUID player, boolean afk, String reason);

    /** Marks recent activity for a player, clearing auto-AFK timers. */
    void markActivity(UUID player);

    /** Seconds the player has been continuously idle, or {@code 0} if active. */
    long idleSeconds(UUID player);
}
