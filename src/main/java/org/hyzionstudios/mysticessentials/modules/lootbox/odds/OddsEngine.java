package org.hyzionstudios.mysticessentials.modules.lootbox.odds;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;

/**
 * Exact odds arithmetic. Weights are positive {@code long}s; {@code EXACT_PERCENT}
 * pools are scaled by 10⁶ so they sum to exactly {@link #PERCENT_TOTAL}.
 * Probabilities are only ever turned into decimals for display
 * ({@link OddsFormatter}); the draw works on the integers.
 *
 * <p>Phase 3 makes the table personal (Design Bible §07, "explicit algorithm"):
 * {@link #table(LootBoxRevision, String, PlayerOdds)} takes the published
 * revision, removes what the player's conditions rule out (or blocks), removes
 * owned unique rewards, applies pity, and normalises the integer weights that
 * remain. The preview shows exactly that table, its hash binds the confirmation
 * to it, and the draw uses it, so the personal odds a player sees are the odds
 * the server rolls (LB-003).</p>
 *
 * <p>Engine-free on purpose: this is the money-like logic and is checked by
 * {@code verifyLootBox} without a server.</p>
 */
public final class OddsEngine {

    /** Decimal places an {@code EXACT_PERCENT} entry may carry. */
    public static final int PERCENT_SCALE = 6;
    /** 100% expressed in {@code EXACT_PERCENT} weight units. */
    public static final long PERCENT_TOTAL = 100L * 1_000_000L;
    /**
     * Soft pity never multiplies a target's weight by more than this (in percent):
     * 1000×. The publish gate checks that the pool's total weight times this still
     * fits a {@code long}.
     */
    public static final long MAX_SOFT_PITY_PERCENT = 100_000L;

    private OddsEngine() {
    }

    // ----- Publishing ---------------------------------------------------------

    /**
     * Resolves a draft's rewards to integer weights.
     *
     * @throws IllegalArgumentException with a reader-facing reason when a weight is
     *         missing, not positive, malformed, too precise, or the total overflows.
     */
    public static List<Long> resolveWeights(LootBoxDraft draft) {
        return resolve(draft.rewards, draft.mode, null, "");
    }

    /** Resolves every bonus roll's weights, including its "no bonus" weight. Same failures as {@link #resolveWeights}. */
    public static List<LootBoxRevision.BonusWeights> resolveBonus(LootBoxDraft draft) {
        List<LootBoxRevision.BonusWeights> out = new ArrayList<>();
        for (LootBoxDraft.BonusRoll roll : draft.bonusRolls()) {
            String label = "bonus roll '" + roll.id + "': ";
            boolean percent = "EXACT_PERCENT".equalsIgnoreCase(roll.mode);
            long none = percent ? percentValue(roll.nonePercent, label + "no-bonus percent") : roll.noneWeight;
            if (none < 0) {
                throw new IllegalArgumentException(label + "the no-bonus weight cannot be negative");
            }
            List<Long> weights = resolve(roll.rewards, roll.mode, none, label);
            out.add(new LootBoxRevision.BonusWeights(roll.id, weights, none));
        }
        return out;
    }

    /** {@code none} is the bonus roll's no-bonus weight, already part of the total; {@code null} for the main table. */
    private static List<Long> resolve(List<LootBoxDraft.RewardSpec> rewards, String mode, Long none, String label) {
        List<Long> weights = new ArrayList<>();
        boolean percent = "EXACT_PERCENT".equalsIgnoreCase(mode);
        long total = none == null ? 0 : none;
        for (LootBoxDraft.RewardSpec reward : rewards == null ? List.<LootBoxDraft.RewardSpec>of() : rewards) {
            long weight = percent ? percentWeight(reward, label) : reward.weight;
            if (weight <= 0) {
                throw new IllegalArgumentException(label + "reward '" + reward.id + "' has no positive "
                        + (percent ? "percent" : "weight"));
            }
            try {
                total = Math.addExact(total, weight);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(label + "total weight overflows a 64-bit integer");
            }
            weights.add(weight);
        }
        if (percent && total != PERCENT_TOTAL) {
            throw new IllegalArgumentException(label + "percentages sum to "
                    + BigDecimal.valueOf(total, PERCENT_SCALE).stripTrailingZeros().toPlainString()
                    + "%, not exactly 100%");
        }
        if (none != null && total <= 0) {
            throw new IllegalArgumentException(label + "has no rewards and no no-bonus weight");
        }
        return weights;
    }

    private static long percentWeight(LootBoxDraft.RewardSpec reward, String label) {
        return percentValue(reward.percent, label + "reward '" + reward.id + "' percent");
    }

    private static long percentValue(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " is unreadable: '" + raw + "'");
        }
        if (value.stripTrailingZeros().scale() > PERCENT_SCALE) {
            throw new IllegalArgumentException(what + " has more than " + PERCENT_SCALE + " decimal places");
        }
        try {
            return value.movePointRight(PERCENT_SCALE).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(what + " is out of range");
        }
    }

    // ----- The table a player sees and draws from -----------------------------

    /** The table with nothing personal: every reward eligible with its published weight. */
    public static OddsTable table(LootBoxRevision revision, String policyProfile) {
        return table(revision, policyProfile, PlayerOdds.NONE);
    }

    /**
     * The table {@code player} sees and draws from. Steps, in the bible's order:
     * conditions (leave out, or block in {@code BLOCK} mode), duplicate protection
     * (leave out owned unique rewards), pity (only targets on a hard-pity opening;
     * targets' weights raised by soft pity), then the integer weights that remain
     * are the distribution. Bonus rolls get the same eligibility and duplicate
     * steps; pity only applies to the main table.
     */
    public static OddsTable table(LootBoxRevision revision, String policyProfile, PlayerOdds player) {
        LootBoxDraft spec = revision.spec;
        List<String> notes = new ArrayList<>();
        List<OddsTable.Excluded> excluded = new ArrayList<>();
        String blocked = player.blocked();
        boolean blockMode = spec.blocksIneligible();

        List<OddsTable.Entry> entries = new ArrayList<>();
        int ineligible = 0;
        int owned = 0;
        for (int i = 0; i < spec.rewards.size(); i++) {
            LootBoxDraft.RewardSpec reward = spec.rewards.get(i);
            long weight = revision.weights.get(i);
            String why = player.ineligible().get(reward.id);
            if (why != null) {
                if (blockMode) {
                    if (blocked == null) {
                        blocked = plain(reward.name) + " isn't available to you (" + why
                                + "), and this pool doesn't recalculate its odds, so you can't open it.";
                    }
                } else {
                    excluded.add(new OddsTable.Excluded(reward.id, i, null, why));
                    ineligible++;
                    continue;
                }
            }
            if (reward.excludesOwned() && player.owned().contains(reward.id)) {
                excluded.add(new OddsTable.Excluded(reward.id, i, null, "already owned"));
                owned++;
                continue;
            }
            entries.add(new OddsTable.Entry(reward.id, i, weight));
        }

        // Pity (main table only).
        int sincePity = 0;
        boolean hard = false;
        long softPercent = 0;
        LootBoxDraft.Pity pity = spec.pity;
        if (pity != null && pity.targets != null && !pity.targets.isEmpty()) {
            sincePity = player.sincePity();
            Set<String> targets = new HashSet<>(pity.targets);
            boolean anyTarget = entries.stream().anyMatch(e -> targets.contains(e.rewardId()));
            String targetNames = names(spec, pity.targets);
            if (pity.hardAfter > 0) {
                notes.add("Guarantee: one of " + targetNames + " within " + pity.hardAfter + " openings. You have opened "
                        + sincePity + " without one.");
            }
            if (!anyTarget) {
                notes.add("The guarantee can't help you: none of " + targetNames + " is in your odds.");
            } else if (pity.hardAfter > 0 && sincePity >= pity.hardAfter - 1) {
                hard = true;
                entries.removeIf(entry -> !targets.contains(entry.rewardId()));
                notes.add("Your next opening is guaranteed to be one of " + targetNames + ".");
            } else if (pity.softAfter > 0 && pity.softStepPercent > 0 && sincePity >= pity.softAfter) {
                long steps = (long) sincePity - pity.softAfter + 1;
                softPercent = Math.min(MAX_SOFT_PITY_PERCENT - 100, steps * pity.softStepPercent);
                long factor = 100 + softPercent;
                List<OddsTable.Entry> raised = new ArrayList<>();
                for (OddsTable.Entry entry : entries) {
                    long weight = Math.multiplyExact(entry.weight(), targets.contains(entry.rewardId()) ? factor : 100);
                    raised.add(new OddsTable.Entry(entry.rewardId(), entry.index(), weight));
                }
                entries = raised;
                notes.add("Pity: " + targetNames + " are " + softPercent + "% more likely on your next opening.");
            } else if (pity.softAfter > 0 && pity.softStepPercent > 0) {
                notes.add("After " + pity.softAfter + " openings without one, " + targetNames + " get "
                        + pity.softStepPercent + "% more likely with each opening.");
            }
        }
        if (ineligible > 0) {
            notes.add(0, ineligible + (ineligible == 1 ? " reward isn't" : " rewards aren't")
                    + " available to you, so the chances of the rest were recalculated.");
        }
        if (owned > 0) {
            notes.add(0, "You already own " + owned + " unique reward" + (owned == 1 ? "" : "s")
                    + "; " + (owned == 1 ? "it is" : "they are") + " left out of your odds.");
        }
        long total = 0;
        for (OddsTable.Entry entry : entries) {
            total = Math.addExact(total, entry.weight());
        }

        // Bonus rolls.
        List<OddsTable.BonusTable> bonus = new ArrayList<>();
        List<LootBoxDraft.BonusRoll> rolls = spec.bonusRolls();
        for (int b = 0; b < rolls.size(); b++) {
            LootBoxDraft.BonusRoll roll = rolls.get(b);
            LootBoxRevision.BonusWeights weights = b < revision.bonusWeights.size() ? revision.bonusWeights.get(b)
                    : new LootBoxRevision.BonusWeights(roll.id, List.of(), 1);
            List<OddsTable.Entry> bonusEntries = new ArrayList<>();
            long bonusTotal = weights.none;
            for (int j = 0; j < roll.rewards.size(); j++) {
                LootBoxDraft.RewardSpec reward = roll.rewards.get(j);
                String key = PlayerOdds.key(roll.id, reward.id);
                String why = player.ineligible().get(key);
                if (why != null) {
                    if (blockMode) {
                        if (blocked == null) {
                            blocked = plain(reward.name) + " isn't available to you (" + why
                                    + "), and this pool doesn't recalculate its odds, so you can't open it.";
                        }
                    } else {
                        excluded.add(new OddsTable.Excluded(reward.id, j, roll.id, why));
                        continue;
                    }
                }
                if (reward.excludesOwned() && player.owned().contains(key)) {
                    excluded.add(new OddsTable.Excluded(reward.id, j, roll.id, "already owned"));
                    continue;
                }
                long weight = weights.weights.get(j);
                bonusEntries.add(new OddsTable.Entry(reward.id, j, weight));
                bonusTotal = Math.addExact(bonusTotal, weight);
            }
            bonus.add(new OddsTable.BonusTable(roll.id, roll.label == null || roll.label.isBlank() ? roll.id
                    : roll.label, b, List.copyOf(bonusEntries), weights.none, bonusTotal));
        }

        OddsTable.Personal personal = new OddsTable.Personal(List.copyOf(notes), List.copyOf(excluded), blocked,
                sincePity, hard, softPercent);
        boolean pityConfigured = pity != null && pity.targets != null && !pity.targets.isEmpty();
        String hash = snapshotHash(revision.lootboxId, revision.revision, revision.contentHash, entries,
                policyProfile, bonus, personal, pityConfigured);
        return new OddsTable(revision.lootboxId, revision.revision, revision.contentHash, List.copyOf(entries),
                total, hash, List.copyOf(bonus), personal);
    }

    /** SHA-256 over the canonical description of what the player is shown (no bonus, nothing personal). */
    static String snapshotHash(String lootboxId, int revision, String contentHash, List<OddsTable.Entry> entries,
            String policyProfile) {
        return snapshotHash(lootboxId, revision, contentHash, entries, policyProfile, List.of(), OddsTable.Personal.NONE,
                false);
    }

    /**
     * SHA-256 over the canonical description of what the player is shown. A pool
     * with no bonus roll, nothing excluded and no pity hashes exactly as in Phase
     * 1, so confirmations and Odds IDs of plain pools did not change; anything
     * personal adds a section, so a pity step, a newly owned reward or a changed
     * condition makes an earlier confirmation stale.
     */
    static String snapshotHash(String lootboxId, int revision, String contentHash, List<OddsTable.Entry> entries,
            String policyProfile, List<OddsTable.BonusTable> bonus, OddsTable.Personal personal,
            boolean pityConfigured) {
        StringBuilder canonical = new StringBuilder()
                .append(lootboxId).append('\n')
                .append(revision).append('\n')
                .append(contentHash).append('\n')
                .append(policyProfile == null ? "" : policyProfile.toUpperCase(Locale.ROOT)).append('\n');
        appendSorted(canonical, entries);
        for (OddsTable.BonusTable table : bonus) {
            canonical.append("bonus ").append(table.rollId()).append(" none=").append(table.none()).append('\n');
            appendSorted(canonical, table.entries());
        }
        if (pityConfigured || personal.isPersonal()) {
            canonical.append("personal pity=").append(personal.sincePity())
                    .append(personal.hardPity() ? " hard" : "")
                    .append(" soft=").append(personal.softPityPercent()).append('\n');
            Set<String> out = new TreeSet<>();
            personal.excluded().forEach(e -> out.add(PlayerOdds.key(e.bonusRoll(), e.rewardId()) + ":" + e.reason()));
            canonical.append("excluded ").append(String.join(",", out)).append('\n');
            canonical.append("blocked ").append(personal.blocked() == null ? "" : personal.blocked()).append('\n');
        }
        return sha256(canonical.toString());
    }

    private static void appendSorted(StringBuilder canonical, List<OddsTable.Entry> entries) {
        entries.stream()
                .sorted((a, b) -> a.rewardId().compareTo(b.rewardId()))
                .forEach(entry -> canonical.append(entry.rewardId()).append('=').append(entry.weight())
                        .append('\n'));
    }

    // ----- The draw -----------------------------------------------------------

    /** One bonus roll's result; {@code entry} is {@code null} for "no bonus" (or a roll with nothing left). */
    public record BonusResult(OddsTable.BonusTable table, long roll, OddsTable.Entry entry) {
    }

    /** The main outcome and every bonus roll's result. */
    public record Draw(long roll, OddsTable.Entry entry, List<BonusResult> bonus) {
    }

    /**
     * Rolls the main table and then each bonus roll, independently, in the
     * revision's order. A bonus roll whose total is 0 (everything left out, no
     * "no bonus" weight) is skipped without a roll.
     */
    public static Draw draw(OddsTable table, SecureDraw random) {
        long roll = random.roll(table.total());
        OddsTable.Entry entry = table.pick(roll);
        List<BonusResult> bonus = new ArrayList<>();
        for (OddsTable.BonusTable roller : table.bonus()) {
            if (roller.total() <= 0) {
                bonus.add(new BonusResult(roller, -1, null));
                continue;
            }
            long bonusRoll = random.roll(roller.total());
            bonus.add(new BonusResult(roller, bonusRoll, roller.pick(bonusRoll)));
        }
        return new Draw(roll, entry, List.copyOf(bonus));
    }

    // ----- Helpers ------------------------------------------------------------

    /** Probability of one entry as an exact fraction {@code weight / total}, reduced. */
    public static BigInteger[] fraction(long weight, long total) {
        BigInteger w = BigInteger.valueOf(weight);
        BigInteger t = BigInteger.valueOf(total);
        BigInteger gcd = w.gcd(t);
        return new BigInteger[] {w.divide(gcd), t.divide(gcd)};
    }

    private static String names(LootBoxDraft spec, List<String> rewardIds) {
        Set<String> names = new LinkedHashSet<>();
        for (String id : rewardIds) {
            String name = id;
            for (LootBoxDraft.RewardSpec reward : spec.rewards) {
                if (id.equals(reward.id)) {
                    name = plain(reward.name == null ? id : reward.name);
                }
            }
            names.add(name);
        }
        return String.join(", ", names);
    }

    private static String plain(String text) {
        return text == null ? "" : MysticText.stripMarkup(text);
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
