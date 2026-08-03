package org.hyzionstudios.mysticessentials.api.service;

import java.util.UUID;

/**
 * Playtime accounting for online players. Time accrues into the player's
 * {@link org.hyzionstudios.mysticessentials.api.model.PlayerProfile} and is
 * split by AFK state: every second is counted as <b>total</b>, and additionally
 * as either <b>idle</b> (the player was AFK) or <b>active</b>.
 *
 * <p>Values are live — they include the part of the current session that has
 * not been written to the profile yet. Offline players report their last
 * persisted totals only while their profile is still cached; otherwise
 * {@code 0}.</p>
 */
public interface PlaytimeService {

    /** Total seconds the player has ever been online. */
    long totalPlaytimeSeconds(UUID player);

    /** Seconds the player has been online and not AFK. */
    long activePlaytimeSeconds(UUID player);

    /** Seconds the player has been online while AFK. */
    long idlePlaytimeSeconds(UUID player);

    /** Seconds elapsed in the player's current session, or {@code 0} if offline. */
    long sessionSeconds(UUID player);
}
