package org.hyzionstudios.mysticessentials.modules.lootbox.odds;

import java.util.List;

/**
 * The effective odds of one pool for one player at one moment: every eligible
 * outcome with its integer weight. The preview, the confirm screen and the draw
 * all use the same table, and its {@link #hash} proves it: the server rejects a
 * confirmation whose hash no longer matches.
 */
public record OddsTable(String lootboxId, int revision, String contentHash, List<Entry> entries, long total,
        String hash) {

    /** One outcome: {@code index} into the revision's reward list. */
    public record Entry(String rewardId, int index, long weight) {
    }

    /** Short form of the hash shown to players as "Odds ID". */
    public String oddsId() {
        return hash.length() >= 8 ? hash.substring(0, 8) : hash;
    }

    /** @return the entry whose cumulative range contains {@code roll}, a value in {@code [0, total)}. */
    public Entry pick(long roll) {
        if (roll < 0 || roll >= total) {
            throw new IllegalArgumentException("roll " + roll + " outside [0, " + total + ")");
        }
        long cumulative = 0;
        for (Entry entry : entries) {
            cumulative += entry.weight();
            if (roll < cumulative) {
                return entry;
            }
        }
        throw new IllegalStateException("weights do not sum to " + total);
    }

    public Entry entry(String rewardId) {
        for (Entry entry : entries) {
            if (entry.rewardId().equals(rewardId)) {
                return entry;
            }
        }
        return null;
    }
}
