package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;

import com.google.gson.JsonObject;

/**
 * Append-only audit trail (§4.11). Every stage of an opening is its own event,
 * as are publishes, grants, revokes and crate changes. Entries go to hourly
 * buckets so the retention sweep drops whole buckets; in-memory counters back
 * {@code /lootbox stats}.
 */
public final class AuditService {

    private static final DateTimeFormatter BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    private final MysticCore core;
    private final LootBoxStorage storage;
    private volatile LootBoxConfig config;
    private final Map<String, AtomicLong> counters = new java.util.concurrent.ConcurrentHashMap<>();

    public AuditService(MysticCore core, LootBoxStorage storage, LootBoxConfig config) {
        this.core = core;
        this.storage = storage;
        this.config = config;
    }

    public void updateConfig(LootBoxConfig config) {
        this.config = config;
    }

    /** Records one event. Never throws; a failed write is logged. */
    public void record(String actor, String action, String target, String detail) {
        counter(action).incrementAndGet();
        JsonObject entry = new JsonObject();
        entry.addProperty("at", System.currentTimeMillis());
        entry.addProperty("server", serverId());
        entry.addProperty("actor", actor == null ? "" : actor);
        entry.addProperty("action", action);
        entry.addProperty("target", target == null ? "" : target);
        entry.addProperty("detail", detail == null ? "" : detail);
        storage.appendAudit(bucket(Instant.now()), entry).exceptionally(t -> {
            core.log(Level.WARNING, "[lootbox] audit write failed for " + action + " " + target + ": " + t);
            return null;
        });
    }

    public AtomicLong counter(String name) {
        return counters.computeIfAbsent(name, key -> new AtomicLong());
    }

    public Map<String, AtomicLong> counters() {
        return counters;
    }

    /** Drops buckets older than {@code audit.retentionDays}; runs off the world threads. */
    public void sweep() {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Math.max(1, config.audit.retentionDays));
        String oldest = BUCKET.format(Instant.ofEpochMilli(cutoff));
        storage.listAuditBuckets().thenAccept(buckets -> {
            for (String bucket : buckets) {
                if (bucket.length() >= 10 && bucket.substring(0, 10).compareTo(oldest) < 0) {
                    storage.deleteAuditBucket(bucket);
                }
            }
        }).exceptionally(t -> {
            core.log(Level.WARNING, "[lootbox] audit retention sweep failed: " + t);
            return null;
        });
    }

    private String bucket(Instant at) {
        return BUCKET.format(at) + "-" + serverId();
    }

    private String serverId() {
        String id = core.config().storage == null || core.config().storage.redis == null
                ? "" : core.config().storage.redis.serverId;
        String safe = id == null ? "" : id.replaceAll("[^A-Za-z0-9_-]", "");
        return safe.isBlank() ? "local" : safe;
    }
}
