package org.hyzionstudios.mysticessentials.modules.lootbox.odds;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;

/**
 * "What is my chance of getting item X from one opening?" (P3-WP6, plan §4.2).
 * The main draw picks exactly one outcome, so the outcomes that contain X (an
 * item reward, or a bundle with it) add up; each bonus roll is independent of
 * the main draw and of the others, so they combine as
 * {@code 1 − (1 − p_main) · Π (1 − p_bonus)}. Overlapping paths are never simply
 * summed (Design Bible §07). Exact rational arithmetic throughout; the result is
 * formatted with {@link OddsFormatter#percent(BigInteger, BigInteger)}.
 */
public final class ItemChance {

    /** An exact probability {@code numerator / denominator}, reduced. */
    public record Chance(BigInteger numerator, BigInteger denominator) {

        static Chance of(BigInteger numerator, BigInteger denominator) {
            if (denominator.signum() == 0) {
                return new Chance(BigInteger.ZERO, BigInteger.ONE);
            }
            BigInteger gcd = numerator.gcd(denominator);
            if (gcd.signum() == 0) {
                return new Chance(BigInteger.ZERO, BigInteger.ONE);
            }
            return new Chance(numerator.divide(gcd), denominator.divide(gcd));
        }

        public boolean isZero() {
            return numerator.signum() == 0;
        }
    }

    private ItemChance() {
    }

    /** The chance that one opening of {@code table} awards at least one reward matching {@code contains}. */
    public static Chance of(OddsTable table, LootBoxRevision revision, Predicate<LootBoxDraft.RewardSpec> contains) {
        // q = probability of getting it through no path at all, as an exact fraction.
        BigInteger qNum = BigInteger.ONE;
        BigInteger qDen = BigInteger.ONE;
        if (table.total() > 0) {
            long hit = 0;
            for (OddsTable.Entry entry : table.entries()) {
                if (contains.test(revision.spec.rewards.get(entry.index()))) {
                    hit += entry.weight();
                }
            }
            qNum = qNum.multiply(BigInteger.valueOf(table.total() - hit));
            qDen = qDen.multiply(BigInteger.valueOf(table.total()));
        }
        List<LootBoxDraft.BonusRoll> rolls = revision.spec.bonusRolls();
        for (OddsTable.BonusTable bonus : table.bonus()) {
            if (bonus.total() <= 0 || bonus.rollIndex() >= rolls.size()) {
                continue;
            }
            LootBoxDraft.BonusRoll roll = rolls.get(bonus.rollIndex());
            long hit = 0;
            for (OddsTable.Entry entry : bonus.entries()) {
                if (contains.test(roll.rewards.get(entry.index()))) {
                    hit += entry.weight();
                }
            }
            qNum = qNum.multiply(BigInteger.valueOf(bonus.total() - hit));
            qDen = qDen.multiply(BigInteger.valueOf(bonus.total()));
        }
        return Chance.of(qDen.subtract(qNum), qDen);
    }

    /** Matches an ITEM reward of {@code itemId}, or a bundle with one. Item ids compare without case. */
    public static Predicate<LootBoxDraft.RewardSpec> containsItem(String itemId) {
        return reward -> {
            for (LootBoxDraft.RewardSpec action : actions(reward)) {
                if ("ITEM".equalsIgnoreCase(action.type) && action.item != null && action.item.itemId != null
                        && action.item.itemId.equalsIgnoreCase(itemId)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** Every distinct item id a revision can award, through any reward, bundle or bonus roll, in order. */
    public static List<String> items(LootBoxRevision revision) {
        Set<String> items = new LinkedHashSet<>();
        add(items, revision.spec.rewards);
        for (LootBoxDraft.BonusRoll roll : revision.spec.bonusRolls()) {
            add(items, roll.rewards);
        }
        return List.copyOf(items);
    }

    /**
     * Whether the item chances differ from the per-reward rows: true when an item
     * can come from more than one path (two rewards, a bundle, or a bonus roll).
     */
    public static boolean worthShowing(LootBoxRevision revision) {
        if (!revision.spec.bonusRolls().isEmpty()) {
            return true;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (LootBoxDraft.RewardSpec reward : revision.spec.rewards) {
            List<LootBoxDraft.RewardSpec> actions = actions(reward);
            if (actions.size() > 1) {
                return true;
            }
            for (LootBoxDraft.RewardSpec action : actions) {
                if (action.item != null && action.item.itemId != null
                        && !seen.add(action.item.itemId.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void add(Set<String> items, List<LootBoxDraft.RewardSpec> rewards) {
        if (rewards == null) {
            return;
        }
        for (LootBoxDraft.RewardSpec reward : rewards) {
            for (LootBoxDraft.RewardSpec action : actions(reward)) {
                if ("ITEM".equalsIgnoreCase(action.type) && action.item != null && action.item.itemId != null
                        && !action.item.itemId.isBlank()) {
                    items.add(action.item.itemId);
                }
            }
        }
    }

    private static List<LootBoxDraft.RewardSpec> actions(LootBoxDraft.RewardSpec reward) {
        if (reward != null && "BUNDLE".equalsIgnoreCase(reward.type)) {
            return reward.bundle == null ? List.of() : reward.bundle;
        }
        return reward == null ? List.of() : List.of(reward);
    }
}
