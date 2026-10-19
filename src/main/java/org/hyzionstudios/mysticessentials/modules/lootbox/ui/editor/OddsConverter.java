package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;

/**
 * Switches a pool draft between {@code WEIGHTED} and {@code EXACT_PERCENT}
 * without changing what it means wherever that is possible, and says so when it
 * is not. Engine-free; {@code verifyLootBox} checks it.
 *
 * <p>Percentages to weights is always exact: each percent is scaled by 10⁶
 * (exactly what {@link OddsEngine#resolveWeights} does) and the weights are
 * divided by their greatest common divisor. Weights to percentages is exact
 * when every share terminates within 6 decimal places; otherwise each share is
 * rounded half-up to 6 places, and the reward with the largest weight absorbs
 * whatever keeps the total at exactly 100. The result lists every reward that
 * moved. A real probability is never changed silently.</p>
 */
public final class OddsConverter {

    public static final String WEIGHTED = "WEIGHTED";
    public static final String EXACT_PERCENT = "EXACT_PERCENT";

    private static final int PLACES = 6;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * What a switch did.
     *
     * @param problem why nothing changed, or {@code null} when the draft switched
     * @param exact   whether every reward kept its exact chance
     * @param notes   one line per reward whose chance had to be rounded
     */
    public record Result(String problem, boolean exact, List<String> notes) {

        public Result {
            notes = List.copyOf(notes);
        }

        public boolean ok() {
            return problem == null;
        }

        static Result refused(String why) {
            return new Result(why, true, List.of());
        }
    }

    private OddsConverter() {
    }

    /** Switches {@code draft} to {@code mode} in place; a no-op when it is already there. */
    public static Result switchTo(LootBoxDraft draft, String mode) {
        String current = EXACT_PERCENT.equalsIgnoreCase(draft.mode) ? EXACT_PERCENT : WEIGHTED;
        if (EXACT_PERCENT.equalsIgnoreCase(mode)) {
            return current.equals(EXACT_PERCENT) ? new Result(null, true, List.of()) : toPercent(draft);
        }
        if (WEIGHTED.equalsIgnoreCase(mode)) {
            return current.equals(WEIGHTED) ? new Result(null, true, List.of()) : toWeights(draft);
        }
        return Result.refused("unknown odds mode '" + mode + "'");
    }

    /** {@code WEIGHTED} → {@code EXACT_PERCENT}, rounding only when a share does not terminate. */
    public static Result toPercent(LootBoxDraft draft) {
        List<LootBoxDraft.RewardSpec> rewards = draft.rewards;
        if (rewards.isEmpty()) {
            draft.mode = EXACT_PERCENT;
            return new Result(null, true, List.of());
        }
        BigInteger total = BigInteger.ZERO;
        for (LootBoxDraft.RewardSpec reward : rewards) {
            if (reward.weight <= 0) {
                return Result.refused("reward '" + reward.id + "' has no positive weight to convert");
            }
            total = total.add(BigInteger.valueOf(reward.weight));
        }
        BigDecimal totalDecimal = new BigDecimal(total);
        List<BigDecimal> shares = new ArrayList<>(rewards.size());
        boolean exact = true;
        BigDecimal sum = BigDecimal.ZERO;
        int largest = 0;
        for (int i = 0; i < rewards.size(); i++) {
            BigDecimal precise = HUNDRED.multiply(BigDecimal.valueOf(rewards.get(i).weight));
            BigDecimal rounded = precise.divide(totalDecimal, PLACES, RoundingMode.HALF_UP);
            if (rounded.multiply(totalDecimal).compareTo(precise) != 0) {
                exact = false;
            }
            if (rounded.signum() <= 0) {
                // A share below 0.0000005% would round to nothing; percent mode can't express it.
                return Result.refused("reward '" + rewards.get(i).id + "' is rarer than percent mode can show "
                        + "(0.000001%); keep this pool on weights");
            }
            shares.add(rounded);
            sum = sum.add(rounded);
            if (rewards.get(i).weight > rewards.get(largest).weight) {
                largest = i;
            }
        }
        List<String> notes = new ArrayList<>();
        BigDecimal remainder = HUNDRED.subtract(sum);
        if (remainder.signum() != 0) {
            BigDecimal adjusted = shares.get(largest).add(remainder);
            shares.set(largest, adjusted);
            notes.add("'" + rewards.get(largest).id + "' absorbs " + plain(remainder) + "% so the total stays 100%");
        }
        if (!exact) {
            for (int i = 0; i < rewards.size(); i++) {
                BigDecimal precise = HUNDRED.multiply(BigDecimal.valueOf(rewards.get(i).weight))
                        .divide(totalDecimal, 12, RoundingMode.HALF_UP);
                // The largest reward's move is already explained by the "absorbs" note.
                boolean explained = i == largest && remainder.signum() != 0;
                if (precise.compareTo(shares.get(i)) != 0 && !explained) {
                    notes.add("'" + rewards.get(i).id + "' rounded to " + plain(shares.get(i)) + "%");
                }
            }
        }
        for (int i = 0; i < rewards.size(); i++) {
            rewards.get(i).percent = plain(shares.get(i));
            rewards.get(i).weight = 0;
        }
        draft.mode = EXACT_PERCENT;
        return new Result(null, exact && remainder.signum() == 0, notes);
    }

    /** {@code EXACT_PERCENT} → {@code WEIGHTED}; always exact. */
    public static Result toWeights(LootBoxDraft draft) {
        if (draft.rewards.isEmpty()) {
            draft.mode = WEIGHTED;
            return new Result(null, true, List.of());
        }
        List<Long> scaled;
        try {
            scaled = OddsEngine.resolveWeights(draft);
        } catch (IllegalArgumentException e) {
            return Result.refused(e.getMessage());
        }
        long divisor = 0;
        for (long weight : scaled) {
            divisor = gcd(divisor, weight);
        }
        for (int i = 0; i < draft.rewards.size(); i++) {
            draft.rewards.get(i).weight = scaled.get(i) / Math.max(1, divisor);
            draft.rewards.get(i).percent = null;
        }
        draft.mode = WEIGHTED;
        return new Result(null, true, List.of());
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
