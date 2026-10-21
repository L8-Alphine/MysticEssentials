package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsFormatter;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;

/**
 * One simulation run as the Simulation screen shows it (P2-H): for every reward
 * its theoretical chance, the observed share, the count, and the gap between
 * them, plus the bar widths and the small-sample warnings. Engine-free, so
 * {@code verifyLootBoxScreens} checks it.
 *
 * <p>The gap is measured in standard errors of the observed share,
 * {@code sqrt(p(1-p)/n)}: within 2 is ordinary luck, 2 to 3 is worth a second
 * run, beyond 3 is unusual. A reward expected fewer than {@link #MIN_EXPECTED}
 * times gets no verdict, because the normal approximation means nothing there.</p>
 *
 * @param draws     draws actually made (the counts' sum)
 * @param maxTrials the {@code simulation.maxTrials} cap the run was made under
 */
public record SimulationReport(String lootboxId, int revision, String oddsId, long draws, long maxTrials,
        List<Row> rows, List<String> warnings) {

    /** Width in pixels of the longest bar; the largest share in the run fills it. */
    public static final int BAR_WIDTH = 180;
    /** Smallest bar for a share above zero, so a rare reward still shows. */
    public static final int MIN_BAR = 2;
    /** Below this expected count a reward's observed rate says little. */
    public static final double MIN_EXPECTED = 10.0;

    /** How far the observed share landed from the chance. */
    public enum Verdict {
        /** Within 2 standard errors. */
        WITHIN,
        /** 2 to 3 standard errors. */
        WATCH,
        /** More than 3 standard errors. */
        UNUSUAL,
        /** Expected fewer than {@link #MIN_EXPECTED} times: no verdict. */
        FEW_DRAWS
    }

    /**
     * One reward.
     *
     * @param chance      the exact chance as the preview prints it
     * @param theoretical the chance in percent
     * @param observed    the observed share in percent
     * @param expected    draws × chance
     * @param gap         observed − theoretical, in percentage points
     * @param sigmas      the gap in standard errors (0 when the chance is 0 or 100%)
     */
    public record Row(String rewardId, String name, String rarity, String chance, double theoretical,
            double observed, long count, double expected, double gap, double sigmas, Verdict verdict,
            int theoryBar, int observedBar) {

        /** {@code 12.3456%}. */
        public String observedText() {
            return String.format(Locale.ROOT, "%.4f%%", observed);
        }

        /** {@code 1,023 · exp. 1,000}. */
        public String countText() {
            return String.format(Locale.ROOT, "%,d", count) + " · exp. " + expectedText(expected);
        }

        /** The signed gap in points, coloured by its verdict (MysticText markup). */
        public String gapText() {
            String colour = switch (verdict) {
                case WITHIN -> "&a";
                case WATCH -> "&e";
                case UNUSUAL -> "&c";
                case FEW_DRAWS -> "&7";
            };
            return colour + String.format(Locale.ROOT, "%+.4f", gap)
                    + (verdict == Verdict.FEW_DRAWS ? " &8few draws" : "");
        }
    }

    /**
     * @param revision  the revision that was simulated
     * @param table     its odds table, the one {@code OpeningService.simulate} drew from
     * @param counts    hits per reward, indexed like the revision's rewards
     * @param formatter prints the theoretical chance exactly as the preview does
     */
    public static SimulationReport of(LootBoxRevision revision, OddsTable table, long[] counts,
            OddsFormatter formatter, long maxTrials) {
        long draws = 0;
        for (long count : counts) {
            draws += count;
        }
        List<LootBoxDraft.RewardSpec> rewards = revision.spec == null ? List.of() : revision.spec.rewards;
        double widest = 0;
        for (OddsTable.Entry entry : table.entries()) {
            widest = Math.max(widest, Math.max(share(entry.weight(), table.total()),
                    draws == 0 ? 0 : (double) count(counts, entry.index()) / draws));
        }
        List<Row> rows = new ArrayList<>();
        OddsTable.Entry rarest = null;
        int unusual = 0;
        for (OddsTable.Entry entry : table.entries()) {
            LootBoxDraft.RewardSpec reward = entry.index() < rewards.size() ? rewards.get(entry.index()) : null;
            double p = share(entry.weight(), table.total());
            long count = count(counts, entry.index());
            double observed = draws == 0 ? 0 : (double) count / draws;
            double expected = draws * p;
            double error = draws == 0 ? 0 : Math.sqrt(p * (1 - p) / draws);
            double sigmas = error == 0 ? 0 : (observed - p) / error;
            Verdict verdict = expected < MIN_EXPECTED ? Verdict.FEW_DRAWS
                    : Math.abs(sigmas) > 3 ? Verdict.UNUSUAL
                    : Math.abs(sigmas) >= 2 ? Verdict.WATCH
                    : Verdict.WITHIN;
            if (verdict == Verdict.UNUSUAL) {
                unusual++;
            }
            if (rarest == null || entry.weight() < rarest.weight()) {
                rarest = entry;
            }
            rows.add(new Row(entry.rewardId(), name(reward, entry.rewardId()),
                    reward == null || reward.rarity == null ? "" : MysticText.stripMarkup(reward.rarity),
                    formatter.percent(entry.weight(), table.total()), p * 100, observed * 100, count, expected,
                    (observed - p) * 100, sigmas, verdict, bar(p, widest), bar(observed, widest)));
        }
        return new SimulationReport(revision.lootboxId, revision.revision, table.oddsId(), draws, maxTrials,
                List.copyOf(rows), warnings(rows, table, rarest, draws, maxTrials, unusual));
    }

    /** The small-sample warning for the rarest reward, and one for unusual gaps. MysticText markup. */
    static List<String> warnings(List<Row> rows, OddsTable table, OddsTable.Entry rarest, long draws, long maxTrials,
            int unusual) {
        List<String> warnings = new ArrayList<>();
        if (rarest != null && rarest.weight() > 0) {
            Row row = rows.stream().filter(r -> r.rewardId().equals(rarest.rewardId())).findFirst().orElse(null);
            double expected = (double) draws * rarest.weight() / table.total();
            if (row != null && expected < MIN_EXPECTED) {
                long needed = drawsFor(rarest.weight(), table.total());
                warnings.add("&eToo few draws for the rarest reward: &f" + row.name() + " &e(" + row.chance()
                        + ") is expected only &f" + expectedText(expected) + " &etime(s) in &f"
                        + String.format(Locale.ROOT, "%,d", draws) + " &edraws, so its observed rate says little. "
                        + (needed <= maxTrials
                                ? "Run at least &f" + String.format(Locale.ROOT, "%,d", needed) + " &edraws."
                                : "Even the maximum of &f" + String.format(Locale.ROOT, "%,d", maxTrials)
                                        + " &edraws expects it only &f"
                                        + expectedText((double) maxTrials * rarest.weight() / table.total())
                                        + " &etime(s); trust the exact chance instead."));
            }
        }
        if (unusual > 0) {
            warnings.add("&e" + unusual + " reward(s) landed more than 3 standard errors from their chance. Luck does "
                    + "that about once in 370 runs per reward; run again before you worry.");
        }
        return List.copyOf(warnings);
    }

    /** The fewest draws that expect a reward of {@code weight} at least {@link #MIN_EXPECTED} times. */
    public static long drawsFor(long weight, long total) {
        if (weight <= 0 || total <= 0) {
            return Long.MAX_VALUE;
        }
        double needed = Math.ceil(MIN_EXPECTED * total / weight);
        return needed >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) needed;
    }

    /** Bar width in pixels for {@code share}, scaled so {@code widest} fills {@link #BAR_WIDTH}. */
    static int bar(double share, double widest) {
        if (share <= 0 || widest <= 0) {
            return 0;
        }
        int width = (int) Math.round(BAR_WIDTH * share / widest);
        return Math.max(MIN_BAR, Math.min(BAR_WIDTH, width));
    }

    private static double share(long weight, long total) {
        return total <= 0 ? 0 : (double) weight / total;
    }

    private static long count(long[] counts, int index) {
        return index >= 0 && index < counts.length ? counts[index] : 0;
    }

    private static String name(LootBoxDraft.RewardSpec reward, String fallback) {
        return reward == null || reward.name == null || reward.name.isBlank() ? fallback
                : MysticText.stripMarkup(reward.name);
    }

    static String expectedText(double expected) {
        return expected < 100 ? String.format(Locale.ROOT, "%.1f", expected)
                : String.format(Locale.ROOT, "%,d", Math.round(expected));
    }
}
