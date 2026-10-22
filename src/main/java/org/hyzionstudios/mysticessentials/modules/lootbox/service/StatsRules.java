package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;

/**
 * Statistics and anomaly checks over committed openings (P4-WP4). Engine-free.
 *
 * <ul>
 *   <li>Counts are kept per pool, revision and UTC day. Only openings drawn from
 *       the pool's <b>base</b> odds (no pity, conditions or duplicate
 *       protection changed them) are compared with the theoretical chances;
 *       personalised ones are counted apart, because their odds were
 *       different, by design and visibly.</li>
 *   <li>Per reward, a binomial z-test of the observed count against
 *       {@code n·p}, flagged when {@code |z|} reaches the limit and the expected
 *       count is large enough to mean something.</li>
 *   <li>Per player and pool, the count of rare wins (base chance at or below
 *       the rare threshold) is tested the same way; an unusually lucky player is
 *       flagged ({@code OpeningFlagged}) for staff to look at, never punished
 *       automatically.</li>
 * </ul>
 */
public final class StatsRules {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private StatsRules() {
    }

    /** The stats document of one pool revision on one UTC day. */
    public static String bucket(String lootboxId, int revision, long at) {
        return lootboxId + "@" + revision + "@" + DAY.format(Instant.ofEpochMilli(at));
    }

    /** The day part ({@code yyyyMMdd}) of a bucket, or {@code null}. */
    public static String dayOf(String bucket) {
        int at = bucket == null ? -1 : bucket.lastIndexOf('@');
        return at < 0 || bucket.length() - at != 9 ? null : bucket.substring(at + 1);
    }

    public static String day(long at) {
        return DAY.format(Instant.ofEpochMilli(at));
    }

    /** The binomial z-score of {@code observed} successes in {@code n} trials of chance {@code p}. */
    public static double z(long observed, long n, double p) {
        if (n <= 0 || p <= 0 || p >= 1) {
            return 0;
        }
        double expected = n * p;
        return (observed - expected) / Math.sqrt(expected * (1 - p));
    }

    /** One reward's line of {@code /lootbox stats}. */
    public record Row(String rewardId, String name, long weight, long tableTotal, double chance, long count,
            double expected, double z, boolean anomaly) {
    }

    /**
     * Observed against theoretical for every reward of the base table.
     * {@code anomaly} needs {@code |z| >= zLimit} and an expected count of at
     * least {@code minExpected}.
     */
    public static List<Row> rows(LootBoxRevision revision, OddsTable base, Map<String, Long> counts, long total,
            double zLimit, double minExpected) {
        List<Row> rows = new ArrayList<>();
        for (OddsTable.Entry entry : base.entries()) {
            double chance = base.total() <= 0 ? 0 : (double) entry.weight() / base.total();
            long count = counts.getOrDefault(entry.rewardId(), 0L);
            double expected = total * chance;
            double z = z(count, total, chance);
            String name = revision.spec.rewards.get(entry.index()).name;
            rows.add(new Row(entry.rewardId(), name == null ? entry.rewardId() : name, entry.weight(), base.total(),
                    chance, count, expected, z, expected >= minExpected && Math.abs(z) >= zLimit));
        }
        return rows;
    }

    /** The base chance of a rare win: the summed chance of every reward at or below {@code rarePercent}. */
    public static double rareChance(OddsTable base, double rarePercent) {
        double sum = 0;
        for (OddsTable.Entry entry : base.entries()) {
            double chance = base.total() <= 0 ? 0 : (double) entry.weight() / base.total();
            if (chance * 100.0 <= rarePercent) {
                sum += chance;
            }
        }
        return sum;
    }

    public static boolean isRare(OddsTable base, String rewardId, double rarePercent) {
        for (OddsTable.Entry entry : base.entries()) {
            if (entry.rewardId().equals(rewardId)) {
                return base.total() > 0 && (double) entry.weight() / base.total() * 100.0 <= rarePercent;
            }
        }
        return false;
    }

    /**
     * Whether a player's rare wins are improbable enough to show staff: at
     * least {@code minOpenings} base-odds openings and a z-score of at least
     * {@code zLimit} (one-sided: only luck, never bad luck).
     */
    public static boolean unusualLuck(long openings, long rare, double rareChance, int minOpenings, double zLimit) {
        return openings >= minOpenings && rareChance > 0 && z(rare, openings, rareChance) >= zLimit;
    }
}
