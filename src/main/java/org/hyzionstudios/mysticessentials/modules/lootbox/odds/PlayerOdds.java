package org.hyzionstudios.mysticessentials.modules.lootbox.odds;

import java.util.Map;
import java.util.Set;

/**
 * Everything about one player that changes their table (P3-WP3, P3-WP4), worked
 * out by the service before the table is built: which rewards their conditions
 * rule out, which unique rewards they already own, and their pity counter.
 * Engine-free, so the personalised odds are checked without a server.
 *
 * <p>Reward keys are the reward id for the main table and
 * {@code <bonusRollId>/<rewardId>} for a bonus roll's reward ({@link #key}).</p>
 *
 * @param ineligible reward key → why it is not available to this player
 * @param owned      reward keys of unique rewards the player already has
 * @param sincePity  openings of this pool since the player last won a pity target
 * @param blocked    why the player can't open at all (a pool condition failed, a
 *                   provider could not answer), or {@code null}
 */
public record PlayerOdds(Map<String, String> ineligible, Set<String> owned, int sincePity, String blocked) {

    public static final PlayerOdds NONE = new PlayerOdds(Map.of(), Set.of(), 0, null);

    public PlayerOdds {
        ineligible = Map.copyOf(ineligible);
        owned = Set.copyOf(owned);
        sincePity = Math.max(0, sincePity);
    }

    public static String key(String bonusRoll, String rewardId) {
        return bonusRoll == null ? rewardId : bonusRoll + "/" + rewardId;
    }
}
