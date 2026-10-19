package org.hyzionstudios.mysticessentials.modules.lootbox.odds;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;

/**
 * Exact odds arithmetic. Weights are positive {@code long}s; {@code EXACT_PERCENT}
 * pools are scaled by 10⁶ so they sum to exactly {@link #PERCENT_TOTAL}.
 * Probabilities are only ever turned into decimals for display
 * ({@link OddsFormatter}); the draw works on the integers.
 *
 * <p>Engine-free on purpose: this is the money-like logic and is checked by
 * {@code verifyLootBox} without a server.</p>
 */
public final class OddsEngine {

    /** Decimal places an {@code EXACT_PERCENT} entry may carry. */
    public static final int PERCENT_SCALE = 6;
    /** 100% expressed in {@code EXACT_PERCENT} weight units. */
    public static final long PERCENT_TOTAL = 100L * 1_000_000L;

    private OddsEngine() {
    }

    /**
     * Resolves a draft's rewards to integer weights.
     *
     * @throws IllegalArgumentException with a reader-facing reason when a weight is
     *         missing, not positive, malformed, too precise, or the total overflows.
     */
    public static List<Long> resolveWeights(LootBoxDraft draft) {
        List<Long> weights = new ArrayList<>();
        boolean percent = "EXACT_PERCENT".equalsIgnoreCase(draft.mode);
        long total = 0;
        for (LootBoxDraft.RewardSpec reward : draft.rewards) {
            long weight = percent ? percentWeight(reward) : reward.weight;
            if (weight <= 0) {
                throw new IllegalArgumentException("reward '" + reward.id + "' has no positive "
                        + (percent ? "percent" : "weight"));
            }
            try {
                total = Math.addExact(total, weight);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("total weight overflows a 64-bit integer");
            }
            weights.add(weight);
        }
        if (percent && total != PERCENT_TOTAL) {
            throw new IllegalArgumentException("percentages sum to "
                    + BigDecimal.valueOf(total, PERCENT_SCALE).stripTrailingZeros().toPlainString()
                    + "%, not exactly 100%");
        }
        return weights;
    }

    private static long percentWeight(LootBoxDraft.RewardSpec reward) {
        if (reward.percent == null || reward.percent.isBlank()) {
            return 0;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(reward.percent.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("reward '" + reward.id + "' has an unreadable percent '"
                    + reward.percent + "'");
        }
        if (value.stripTrailingZeros().scale() > PERCENT_SCALE) {
            throw new IllegalArgumentException("reward '" + reward.id + "' percent has more than "
                    + PERCENT_SCALE + " decimal places");
        }
        try {
            return value.movePointRight(PERCENT_SCALE).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("reward '" + reward.id + "' percent is out of range");
        }
    }

    /**
     * The table a player sees and draws from. Phase 1 has no per-player modifiers,
     * so every reward is eligible with its published weight; the hash still covers
     * the policy profile so a profile change forces a fresh confirmation.
     */
    public static OddsTable table(LootBoxRevision revision, String policyProfile) {
        List<OddsTable.Entry> entries = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < revision.spec.rewards.size(); i++) {
            long weight = revision.weights.get(i);
            entries.add(new OddsTable.Entry(revision.spec.rewards.get(i).id, i, weight));
            total = Math.addExact(total, weight);
        }
        String hash = snapshotHash(revision.lootboxId, revision.revision, revision.contentHash, entries,
                policyProfile);
        return new OddsTable(revision.lootboxId, revision.revision, revision.contentHash, List.copyOf(entries),
                total, hash);
    }

    /** SHA-256 over the canonical description of what the player is shown. */
    static String snapshotHash(String lootboxId, int revision, String contentHash, List<OddsTable.Entry> entries,
            String policyProfile) {
        StringBuilder canonical = new StringBuilder()
                .append(lootboxId).append('\n')
                .append(revision).append('\n')
                .append(contentHash).append('\n')
                .append(policyProfile == null ? "" : policyProfile.toUpperCase(Locale.ROOT)).append('\n');
        entries.stream()
                .sorted((a, b) -> a.rewardId().compareTo(b.rewardId()))
                .forEach(entry -> canonical.append(entry.rewardId()).append('=').append(entry.weight())
                        .append('\n'));
        return sha256(canonical.toString());
    }

    /** Probability of one entry as an exact fraction {@code weight / total}, reduced. */
    public static BigInteger[] fraction(long weight, long total) {
        BigInteger w = BigInteger.valueOf(weight);
        BigInteger t = BigInteger.valueOf(total);
        BigInteger gcd = w.gcd(t);
        return new BigInteger[] {w.divide(gcd), t.divide(gcd)};
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
