package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningCommittedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningFlaggedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Statistics, anomaly checks and the retention sweep (P4-WP4/WP5); the rules
 * are in {@link StatsRules} and {@link RetentionRules}. Counts live in the
 * shared store, one document per pool revision and UTC day, updated atomically,
 * so on a network every server's openings add up in one place.
 *
 * <p>Fed by {@code OpeningCommittedEvent}; all storage work is asynchronous.</p>
 */
public final class StatsService {

    /** What {@code /lootbox stats} shows. */
    public record Report(LootBoxRevision revision, String oddsId, int days, long total, long personal,
            List<StatsRules.Row> rows) {

        public boolean anomaly() {
            return rows.stream().anyMatch(StatsRules.Row::anomaly);
        }
    }

    private final LootBoxStorage storage;
    private final LootBoxRegistry registry;
    private final AuditService audit;
    private final Supplier<LootBoxConfig> config;
    private final Consumer<OpeningFlaggedEvent> flags;
    private final BiConsumer<String, Throwable> log;
    /** Pool revisions whose anomaly was already reported today, so the hourly check doesn't repeat it. */
    private final Map<String, String> reported = new ConcurrentHashMap<>();

    public StatsService(LootBoxStorage storage, LootBoxRegistry registry, AuditService audit,
            Supplier<LootBoxConfig> config, Consumer<OpeningFlaggedEvent> flags, BiConsumer<String, Throwable> log) {
        this.storage = storage;
        this.registry = registry;
        this.audit = audit;
        this.config = config;
        this.flags = flags;
        this.log = log;
    }

    private LootBoxConfig.Stats settings() {
        LootBoxConfig current = config.get();
        return current == null || current.stats == null ? new LootBoxConfig.Stats() : current.stats;
    }

    /** Counts one committed opening and checks the player's luck. */
    public CompletableFuture<Void> onCommitted(OpeningCommittedEvent event, long now) {
        if (!settings().enabled || event.lootboxId() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return revision(event.lootboxId(), event.revision()).thenCompose(revision -> {
            if (revision == null) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            OddsTable base = OddsEngine.table(revision, config.get().policyProfile);
            boolean personal = !base.oddsId().equals(event.oddsId());
            CompletableFuture<JsonElement> counted = storage.updateStats(
                    StatsRules.bucket(event.lootboxId(), event.revision(), now), element -> {
                        JsonObject doc = element != null && element.isJsonObject() ? element.getAsJsonObject()
                                : new JsonObject();
                        if (personal) {
                            doc.addProperty("personal", number(doc, "personal") + 1);
                            return doc;
                        }
                        doc.addProperty("total", number(doc, "total") + 1);
                        JsonObject counts = doc.has("counts") && doc.get("counts").isJsonObject()
                                ? doc.getAsJsonObject("counts") : new JsonObject();
                        counts.addProperty(event.rewardId(), number(counts, event.rewardId()) + 1);
                        doc.add("counts", counts);
                        return doc;
                    });
            if (personal || event.player() == null) {
                return counted.thenApply(ignored -> (Void) null);
            }
            return counted.thenCompose(ignored -> luck(event, revision, base, now));
        }).exceptionally(error -> {
            log.accept("counting opening " + event.openingId() + " failed", error);
            return null;
        });
    }

    private CompletableFuture<Void> luck(OpeningCommittedEvent event, LootBoxRevision revision, OddsTable base,
            long now) {
        LootBoxConfig.Stats s = settings();
        String key = event.lootboxId() + "@" + event.revision();
        boolean rare = StatsRules.isRare(base, event.rewardId(), s.rarePercent);
        double rareChance = StatsRules.rareChance(base, s.rarePercent);
        long[] tally = new long[2];
        boolean[] flag = {false};
        return storage.updatePlayerStats(event.player(), element -> {
            JsonObject doc = element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
            JsonObject pool = doc.has(key) && doc.get(key).isJsonObject() ? doc.getAsJsonObject(key)
                    : new JsonObject();
            long n = number(pool, "n") + 1;
            long wins = number(pool, "rare") + (rare ? 1 : 0);
            pool.addProperty("n", n);
            pool.addProperty("rare", wins);
            tally[0] = n;
            tally[1] = wins;
            flag[0] = false;
            boolean lucky = StatsRules.unusualLuck(n, wins, rareChance, s.luckMinOpenings, s.luckZ);
            if (lucky && now - number(pool, "flaggedAt") >= TimeUnit.DAYS.toMillis(1)) {
                pool.addProperty("flaggedAt", now);
                flag[0] = true;
            }
            doc.add(key, pool);
            return doc;
        }).thenAccept(ignored -> {
            if (flag[0]) {
                String detail = tally[1] + " rare wins in " + tally[0] + " openings; about "
                        + String.format(java.util.Locale.ROOT, "%.1f", tally[0] * rareChance) + " expected";
                audit.record("lootbox", "LUCK_FLAG", event.player().toString(), key + " " + detail);
                flags.accept(new OpeningFlaggedEvent(event.player(), event.lootboxId(), "unusual luck", detail));
            }
        });
    }

    /** Observed against theoretical over the last {@code days} days. */
    public CompletableFuture<Report> report(String lootboxId, int revisionNumber, int days, long now) {
        LootBoxConfig.Stats s = settings();
        int window = Math.max(1, Math.min(days, 366));
        return revision(lootboxId, revisionNumber).thenCompose(revision -> {
            if (revision == null) {
                return CompletableFuture.completedFuture(null);
            }
            List<CompletableFuture<JsonElement>> reads = new ArrayList<>();
            for (int d = 0; d < window; d++) {
                reads.add(storage.loadStats(StatsRules.bucket(lootboxId, revision.revision,
                        now - TimeUnit.DAYS.toMillis(d))));
            }
            return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                Map<String, Long> counts = new LinkedHashMap<>();
                long total = 0;
                long personal = 0;
                for (CompletableFuture<JsonElement> read : reads) {
                    JsonElement element = read.join();
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }
                    JsonObject doc = element.getAsJsonObject();
                    total += number(doc, "total");
                    personal += number(doc, "personal");
                    if (doc.has("counts") && doc.get("counts").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> count : doc.getAsJsonObject("counts").entrySet()) {
                            counts.merge(count.getKey(), count.getValue().getAsLong(), Long::sum);
                        }
                    }
                }
                OddsTable base = OddsEngine.table(revision, config.get().policyProfile);
                return new Report(revision, base.oddsId(), window, total, personal,
                        StatsRules.rows(revision, base, counts, total, s.zLimit, s.minExpected));
            });
        });
    }

    /** Hourly: every live pool's window; an anomaly is audited and logged once per pool revision per day. */
    public void checkAnomalies(long now) {
        if (!settings().enabled) {
            return;
        }
        for (LootBoxRevision revision : registry.publishedPools()) {
            report(revision.lootboxId, revision.revision, settings().windowDays, now).thenAccept(report -> {
                if (report == null || !report.anomaly()) {
                    return;
                }
                String key = revision.lootboxId + "@" + revision.revision;
                String today = StatsRules.day(now);
                if (today.equals(reported.put(key, today))) {
                    return;
                }
                StringBuilder detail = new StringBuilder();
                for (StatsRules.Row row : report.rows()) {
                    if (row.anomaly()) {
                        detail.append(row.rewardId()).append(String.format(java.util.Locale.ROOT, " z=%.1f; ",
                                row.z()));
                    }
                }
                audit.record("lootbox", "ODDS_ANOMALY", key, detail.toString().trim());
                log.accept("observed odds of " + key + " are far from the published ones: " + detail
                        + "check /lootbox stats " + revision.lootboxId, null);
            }).exceptionally(error -> {
                log.accept("anomaly check of " + revision.lootboxId + " failed", error);
                return null;
            });
        }
    }

    /** Daily: drops statistics and settled openings older than the retention (P4-WP5). */
    public void sweep(long now) {
        LootBoxConfig current = config.get();
        LootBoxConfig.Retention retention = current.retention == null ? new LootBoxConfig.Retention()
                : current.retention;
        int statsDays = RetentionRules.effectiveDays(retention.statsDays, current.policyProfile);
        int openingDays = RetentionRules.effectiveDays(retention.openingsDays, current.policyProfile);
        String oldestDay = StatsRules.day(now - TimeUnit.DAYS.toMillis(statsDays));
        storage.listStats().thenAccept(buckets -> {
            for (String bucket : buckets) {
                String day = StatsRules.dayOf(bucket);
                if (day != null && day.compareTo(oldestDay) < 0) {
                    storage.deleteStats(bucket);
                }
            }
        }).exceptionally(error -> {
            log.accept("statistics retention sweep failed", error);
            return null;
        });
        long cutoff = now - TimeUnit.DAYS.toMillis(openingDays);
        storage.listOpenings().thenCompose(ids -> {
            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
            for (String id : ids) {
                // One at a time: a large backlog must not flood the store.
                chain = chain.thenCompose(swept -> storage.loadOpening(id).thenCompose(opening ->
                        RetentionRules.sweepable(opening, cutoff)
                                ? storage.deleteOpening(id).thenApply(deleted -> swept + (deleted ? 1 : 0))
                                : CompletableFuture.completedFuture(swept)));
            }
            return chain;
        }).thenAccept(swept -> {
            if (swept > 0) {
                audit.record("lootbox", "RETENTION_SWEEP", "openings", swept + " settled opening(s) older than "
                        + openingDays + " days");
            }
        }).exceptionally(error -> {
            log.accept("opening retention sweep failed", error);
            return null;
        });
    }

    private CompletableFuture<LootBoxRevision> revision(String lootboxId, int revision) {
        LootBoxRevision live = registry.active(lootboxId).orElse(null);
        if (live != null && (revision <= 0 || live.revision == revision)) {
            return CompletableFuture.completedFuture(live);
        }
        return revision <= 0 ? CompletableFuture.completedFuture(null) : storage.loadRevision(lootboxId, revision);
    }

    private static long number(JsonObject doc, String key) {
        JsonElement value = doc.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsLong() : 0L;
    }
}
