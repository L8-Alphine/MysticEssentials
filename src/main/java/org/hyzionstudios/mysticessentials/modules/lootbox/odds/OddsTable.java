package org.hyzionstudios.mysticessentials.modules.lootbox.odds;

import java.util.List;

/**
 * The effective odds of one pool for one player at one moment: every eligible
 * outcome with its integer weight, every bonus roll's own distribution, and what
 * made the table personal. The preview, the confirm screen and the draw all use
 * the same table, and its {@link #hash} proves it: the server rejects a
 * confirmation whose hash no longer matches.
 *
 * <p>{@link #personal} is never hidden: eligibility, duplicate protection and
 * pity change the weights, and every screen that shows the table says how
 * (Design Bible §07, "label current effective odds and disclose the
 * modifier").</p>
 */
public record OddsTable(String lootboxId, int revision, String contentHash, List<Entry> entries, long total,
        String hash, List<BonusTable> bonus, Personal personal) {

    /** A table with no bonus rolls and nothing personal (the Phase 1 shape). */
    public OddsTable(String lootboxId, int revision, String contentHash, List<Entry> entries, long total,
            String hash) {
        this(lootboxId, revision, contentHash, entries, total, hash, List.of(), Personal.NONE);
    }

    /** One outcome: {@code index} into the revision's reward list (or the bonus roll's). */
    public record Entry(String rewardId, int index, long weight) {
    }

    /**
     * One bonus roll (P3-WP2): its rewards' weights plus {@link #none}, the weight
     * of "no bonus". The chance of a bonus at all is {@code (total - none) / total}.
     * {@code rollIndex} is the roll's position in the revision.
     */
    public record BonusTable(String rollId, String label, int rollIndex, List<Entry> entries, long none, long total) {

        /** @return the entry {@code roll} lands on, or {@code null} for "no bonus". */
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
            return null;
        }

        /** The weight of getting any bonus at all. */
        public long anyWeight() {
            return total - none;
        }
    }

    /** A reward left out of this player's table, and why. {@code bonusRoll} is {@code null} for the main table. */
    public record Excluded(String rewardId, int index, String bonusRoll, String reason) {
    }

    /**
     * What made the table personal. {@code blocked} (when set) is why this player
     * can't open now although the odds are shown; {@code notes} are the
     * plain-language summary every screen prints.
     */
    public record Personal(List<String> notes, List<Excluded> excluded, String blocked, int sincePity,
            boolean hardPity, long softPityPercent) {

        public static final Personal NONE = new Personal(List.of(), List.of(), null, 0, false, 0);

        public boolean isPersonal() {
            return !notes.isEmpty() || !excluded.isEmpty() || blocked != null;
        }
    }

    /** Short form of the hash shown to players as "Odds ID". */
    public String oddsId() {
        return hash.length() >= 8 ? hash.substring(0, 8) : hash;
    }

    /** Why this player can't open now, or {@code null}. A table with no outcome left is always blocked. */
    public String blocked() {
        if (personal.blocked() != null) {
            return personal.blocked();
        }
        return total <= 0 ? "No reward in this pool is available to you." : null;
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

    /** The bonus table of {@code rollId}, or {@code null}. */
    public BonusTable bonus(String rollId) {
        for (BonusTable table : bonus) {
            if (table.rollId().equals(rollId)) {
                return table;
            }
        }
        return null;
    }
}
