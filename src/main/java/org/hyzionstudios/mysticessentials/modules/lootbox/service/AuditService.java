package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;

/**
 * Append-only audit trail (§4.11). Every stage of an opening is its own event,
 * as are publishes, grants, revokes and crate changes. Entries go to hourly
 * buckets so the retention sweep drops whole buckets; in-memory counters back
 * {@code /lootbox stats}. The Audit History screen reads the buckets back
 * through {@link #page} and {@link #export}, one hour at a time ({@link AuditQuery}).
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

    /** Keyed-hash salt for pseudonyms in exports (P4-WP5); set from the key-signing secret at enable. */
    private volatile byte[] pseudonymSalt = new byte[0];

    public void pseudonymSalt(byte[] salt) {
        this.pseudonymSalt = salt == null ? new byte[0] : salt.clone();
    }

    /**
     * Drops buckets older than {@code audit.retentionDays} (at least 365 days under
     * PAID_RANDOM_RESTRICTED, P4-WP5); runs off the world threads.
     */
    public void sweep() {
        int days = RetentionRules.effectiveDays(config.audit.retentionDays, config.policyProfile);
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
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

    // ----- Reading back (Audit History, P2-H) ---------------------------------

    /** Most buckets one page reads: 31 days of one server. Older matches need a narrower filter. */
    public static final int PAGE_MAX_BUCKETS = 24 * 31;
    /** Most buckets one export reads: the default retention of one server. */
    public static final int EXPORT_MAX_BUCKETS = 24 * 400;
    /** Most records one export writes. */
    public static final int EXPORT_MAX_RECORDS = 100_000;

    private static final DateTimeFormatter EXPORT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    /** Two exports in the same second must not pick the same file. */
    private final Object exportNames = new Object();

    /** An export file's format. */
    public enum ExportFormat {
        JSON, CSV;

        String extension() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A finished export: the file and what went into it. */
    public record Export(Path file, AuditQuery.Written written) {
    }

    /** One page of matching records, newest first. Reads storage off the calling thread. */
    public CompletableFuture<AuditQuery.Page> page(AuditQuery.Filter filter, int offset, int limit) {
        return AuditQuery.page(source(), filter, offset, limit, PAGE_MAX_BUCKETS, ForkJoinPool.commonPool());
    }

    /** {@code <data>/modules/lootbox/exports/}, where exports are written. */
    public Path exportsDir() {
        return core.paths().moduleDataDir("lootbox").resolve("exports");
    }

    /**
     * Writes every record matching {@code filter}, newest first, to a new file in
     * {@link #exportsDir()}, at most {@link #EXPORT_MAX_RECORDS} of them. Records
     * stream to a temporary file that is moved into place when complete, so a
     * failed export leaves nothing half-written. The export itself is audited.
     */
    public CompletableFuture<Export> export(AuditQuery.Filter filter, ExportFormat format, String actor,
            boolean identifiers) {
        byte[] salt = pseudonymSalt;
        return CompletableFuture.supplyAsync(() -> {
            try {
                synchronized (exportNames) {
                    Files.createDirectories(exportsDir());
                    String stem = "audit-" + EXPORT_STAMP.format(Instant.now());
                    Path target = exportsDir().resolve(stem + "." + format.extension());
                    for (int n = 2; Files.exists(target) || Files.exists(temp(target)); n++) {
                        target = exportsDir().resolve(stem + "-" + n + "." + format.extension());
                    }
                    // Claimed while the lock is held; the records stream into it below.
                    Files.createFile(temp(target));
                    return target;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, ForkJoinPool.commonPool()).thenCompose(target -> {
            Path temp = temp(target);
            Writer out;
            JsonWriter json;
            try {
                out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
                json = format == ExportFormat.JSON ? AuditQuery.jsonWriter(out) : null;
                if (json != null) {
                    json.beginArray();
                } else {
                    out.write(AuditQuery.CSV_HEADER);
                }
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
            return AuditQuery.export(source(), filter, EXPORT_MAX_RECORDS, EXPORT_MAX_BUCKETS,
                    ForkJoinPool.commonPool(), found -> {
                        // Without the identifiers permission, player UUIDs become pseudonyms (P4-WP5).
                        AuditQuery.Record record = identifiers ? found : new AuditQuery.Record(found.at(),
                                found.server(), RetentionRules.pseudonymise(found.actor(), salt), found.action(),
                                RetentionRules.pseudonymise(found.target(), salt),
                                RetentionRules.pseudonymise(found.detail(), salt));
                        if (json != null) {
                            AuditQuery.json(json, record);
                        } else {
                            out.write(AuditQuery.csvLine(record));
                        }
                    }).handle((written, error) -> {
                        Throwable failure = error;
                        try {
                            if (failure == null) {
                                if (json != null) {
                                    json.endArray();
                                    json.flush();
                                }
                                out.close();
                                move(temp, target);
                                record(actor, "AUDIT_EXPORT", target.getFileName().toString(),
                                        written.records() + " records, " + filter.describe()
                                                + (identifiers ? ", with identifiers" : ", pseudonymised"));
                                return new Export(target, written);
                            }
                            out.close();
                        } catch (IOException e) {
                            failure = e;
                        }
                        try {
                            Files.deleteIfExists(temp);
                        } catch (IOException ignored) {
                            // A stray .tmp file is harmless; the next export picks a new name.
                        }
                        throw failure instanceof CompletionException completion ? completion
                                : new CompletionException(failure);
                    });
        });
    }

    private static Path temp(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private AuditQuery.Source source() {
        return new AuditQuery.Source() {
            @Override
            public CompletableFuture<List<String>> list() {
                return storage.listAuditBuckets();
            }

            @Override
            public CompletableFuture<JsonElement> load(String bucket) {
                return storage.loadAuditBucket(bucket);
            }
        };
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
