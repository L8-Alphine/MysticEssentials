package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;

/**
 * Reads the audit trail back for the Audit History screen (P2-H): filters,
 * newest-first paging and streaming export over the hourly buckets that
 * {@link AuditService#record} appends to. Engine-free, so
 * {@code verifyLootBoxScreens} runs it over an in-memory {@link Source}.
 *
 * <p><b>Bounded.</b> Only bucket <i>keys</i> are listed in full (one short string
 * per server per hour). Buckets are then read one hour at a time, newest first,
 * and dropped once filtered: a page keeps at most {@code offset + limit + 1}
 * records, an export writes each record as it goes, and a scan stops after
 * {@code maxBuckets} buckets and says so. Every step hops to the executor, so a
 * long scan never deepens the stack or holds a world thread.</p>
 */
public final class AuditQuery {

    /** {@code yyyyMMddHH}, the hour prefix of a bucket key. */
    public static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);
    /** How records print on screen and in CSV. */
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    /** "All actions" in the action filter. */
    public static final String ALL = "ALL";
    /** Prefix of an action-group filter value, e.g. {@code group:OPENINGS}. */
    public static final String GROUP = "group:";

    /** Every action the module records, by group, in the order the filter lists them. */
    public static final Map<String, List<String>> GROUPS = groups();

    private static Map<String, List<String>> groups() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("OPENINGS", List.of("OPEN_REQUEST", "OPEN_COMMIT", "OPEN_ABORT", "OPEN_ANOMALY", "KEY_REJECTED",
                "RECOVER", "RECONCILE_REGRANT", "RECONCILE_DISMISS"));
        groups.put("DELIVERY", List.of("DELIVERY_ATTEMPT", "DELIVERED", "DELIVERY_REVIEW", "COMMAND_RUN",
                "COMMAND_REFUSED", "PENDING_CLAIM", "PENDING_EXPIRED"));
        groups.put("KEYS", List.of("KEY_GRANT", "KEY_GRANT_REFUSED", "KEY_REVOKE", "KEY_PUBLISH"));
        groups.put("PUBLISHING", List.of("PUBLISH", "ROLLBACK", "DISABLE", "ENABLE"));
        groups.put("DRAFTS", List.of("DRAFT_CREATE", "DRAFT_SAVE", "DRAFT_OVERWRITE", "DRAFT_CLONE", "DRAFT_ARCHIVE"));
        groups.put("TEMPLATES", List.of("TEMPLATE_SAVE", "TEMPLATE_OVERWRITE", "TEMPLATE_ARCHIVE", "TEMPLATE_APPLY"));
        groups.put("CRATES", List.of("CRATE_CONVERT", "CRATE_REMOVE", "CRATE_DISABLE", "CRATE_ENABLE"));
        groups.put("AUDIT", List.of("AUDIT_EXPORT"));
        return Collections.unmodifiableMap(groups);
    }

    /** One audit entry as {@link AuditService#record} wrote it. */
    public record Record(long at, String server, String actor, String action, String target, String detail) {

        /** Reads one entry; missing fields become {@code ""} (or 0 for the time). */
        public static Record of(JsonElement element) {
            JsonObject entry = element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
            long at = 0;
            try {
                at = entry.has("at") && entry.get("at").isJsonPrimitive() ? entry.get("at").getAsLong() : 0;
            } catch (RuntimeException ignored) {
                // An unreadable time sorts last.
            }
            return new Record(at, text(entry, "server"), text(entry, "actor"), text(entry, "action"),
                    text(entry, "target"), text(entry, "detail"));
        }

        /** {@code 2026-10-20 14:03:22} (UTC). */
        public String time() {
            return TIME.format(Instant.ofEpochMilli(at));
        }

        private static String text(JsonObject entry, String key) {
            JsonElement value = entry.get(key);
            return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
        }
    }

    /**
     * What to keep. Text filters are case-insensitive "contains" matches; blank
     * means any.
     *
     * @param target   matches the target id or the detail text (pool ids appear in an opening's detail)
     * @param actor    matches the actor (a player UUID, a staff name, {@code server} or {@code console})
     * @param actorIds extra exact ids for the actor filter, such as the UUID its name resolved to;
     *                 they also match the target, where grants and claims name the player
     * @param action   {@link #ALL}, {@code group:<name>} or one exact action
     * @param from     earliest time kept (epoch millis, inclusive); 0 for no limit
     * @param to       latest time kept (epoch millis, inclusive); {@link Long#MAX_VALUE} for no limit
     */
    public record Filter(String target, String actor, Set<String> actorIds, String action, long from, long to)
            implements Predicate<Record> {

        public Filter {
            target = target == null ? "" : target.trim();
            actor = actor == null ? "" : actor.trim();
            actorIds = actorIds == null ? Set.of() : Set.copyOf(actorIds);
            action = action == null || action.isBlank() ? ALL : action.trim();
        }

        public static Filter everything() {
            return new Filter("", "", Set.of(), ALL, 0, Long.MAX_VALUE);
        }

        @Override
        public boolean test(Record record) {
            if (record.at() < from || record.at() > to) {
                return false;
            }
            if (!actionMatches(record.action())) {
                return false;
            }
            if (!target.isEmpty() && !contains(record.target(), target) && !contains(record.detail(), target)) {
                return false;
            }
            return actor.isEmpty() || contains(record.actor(), actor) || actorIds.contains(record.actor())
                    || actorIds.contains(record.target());
        }

        private boolean actionMatches(String value) {
            if (ALL.equals(action)) {
                return true;
            }
            if (action.startsWith(GROUP)) {
                List<String> members = GROUPS.get(action.substring(GROUP.length()));
                return members != null && members.contains(value);
            }
            return action.equalsIgnoreCase(value);
        }

        /** Short form for an export's audit entry, e.g. {@code target=royal action=group:KEYS}. */
        public String describe() {
            List<String> parts = new ArrayList<>();
            if (!target.isEmpty()) {
                parts.add("target=" + target);
            }
            if (!actor.isEmpty()) {
                parts.add("actor=" + actor);
            }
            if (!ALL.equals(action)) {
                parts.add("action=" + action);
            }
            if (from > 0) {
                parts.add("from=" + TIME.format(Instant.ofEpochMilli(from)) + "Z");
            }
            return parts.isEmpty() ? "no filter" : String.join(" ", parts);
        }
    }

    /** Where the buckets come from: the storage namespace in the module, a map in the checks. */
    public interface Source {

        /** Every bucket key ({@code yyyyMMddHH-serverId}). */
        CompletableFuture<List<String>> list();

        /** One bucket's entries (a JSON array), or {@code null} when it is gone. */
        CompletableFuture<JsonElement> load(String bucket);
    }

    /**
     * One page of matches, newest first.
     *
     * @param more      at least one older match exists past this page
     * @param scanned   buckets read
     * @param truncated the scan hit its bucket limit before it reached the start of the window,
     *                  so older matches may exist that it did not see
     */
    public record Page(List<Record> records, int offset, boolean more, int scanned, boolean truncated) {
    }

    /** What an export wrote. {@code capped}: it stopped at the record limit. */
    public record Written(int records, int scanned, boolean truncated, boolean capped) {
    }

    private AuditQuery() {
    }

    // ----- Buckets --------------------------------------------------------------

    /**
     * The bucket keys whose hour can hold records in {@code [from, to]}, grouped by
     * hour (one bucket per server), newest hour first. Keys that do not start with
     * an hour are skipped.
     */
    public static List<List<String>> hours(List<String> keys, long from, long to) {
        String first = from <= 0 ? "" : HOUR.format(Instant.ofEpochMilli(from));
        String last = to == Long.MAX_VALUE ? null : HOUR.format(Instant.ofEpochMilli(Math.max(0, to)));
        TreeMap<String, List<String>> byHour = new TreeMap<>(Comparator.reverseOrder());
        for (String key : keys) {
            String hour = hourOf(key);
            if (hour == null || hour.compareTo(first) < 0 || (last != null && hour.compareTo(last) > 0)) {
                continue;
            }
            byHour.computeIfAbsent(hour, h -> new ArrayList<>()).add(key);
        }
        List<List<String>> hours = new ArrayList<>(byHour.size());
        byHour.values().forEach(group -> {
            group.sort(null);
            hours.add(List.copyOf(group));
        });
        return hours;
    }

    /** {@code 2026102014} for {@code 2026102014-lobby}; {@code null} when the key has no hour. */
    static String hourOf(String key) {
        if (key == null || key.length() < 10) {
            return null;
        }
        for (int i = 0; i < 10; i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return null;
            }
        }
        return key.length() == 10 || key.charAt(10) == '-' ? key.substring(0, 10) : null;
    }

    /** Every record of one hour across its servers, newest first, then filtered. */
    static List<Record> merge(List<JsonElement> buckets, Filter filter) {
        List<Record> records = new ArrayList<>();
        for (JsonElement bucket : buckets) {
            if (bucket == null || !bucket.isJsonArray()) {
                continue;
            }
            JsonArray entries = bucket.getAsJsonArray();
            for (JsonElement entry : entries) {
                Record record = Record.of(entry);
                if (filter.test(record)) {
                    records.add(record);
                }
            }
        }
        // Stable: entries of one bucket keep their append order when times tie.
        Collections.reverse(records);
        records.sort(Comparator.comparingLong(Record::at).reversed());
        return records;
    }

    // ----- Paging and export ----------------------------------------------------

    /** Matches {@code offset .. offset + limit - 1}, newest first, reading at most {@code maxBuckets} buckets. */
    public static CompletableFuture<Page> page(Source source, Filter filter, int offset, int limit, int maxBuckets,
            Executor executor) {
        int skip = Math.max(0, offset);
        int wanted = Math.max(1, limit);
        List<Record> window = new ArrayList<>();
        int[] seen = {0};
        return scan(source, filter, maxBuckets, executor, record -> {
            if (seen[0]++ >= skip) {
                window.add(record);
            }
            // One past the page tells whether an older page exists.
            return window.size() <= wanted;
        }).thenApply(scan -> new Page(List.copyOf(window.subList(0, Math.min(wanted, window.size()))), skip,
                window.size() > wanted, scan.scanned(), scan.truncated() && window.size() <= wanted));
    }

    /**
     * Streams every match, newest first, into {@code sink}, stopping after
     * {@code maxRecords}. The sink runs on the executor; an {@link IOException} it
     * throws fails the future.
     */
    public static CompletableFuture<Written> export(Source source, Filter filter, int maxRecords, int maxBuckets,
            Executor executor, RecordSink sink) {
        int[] written = {0};
        return scan(source, filter, maxBuckets, executor, record -> {
            if (written[0] >= maxRecords) {
                return false;
            }
            try {
                sink.write(record);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            written[0]++;
            return written[0] < maxRecords;
        }).thenApply(scan -> new Written(written[0], scan.scanned(), scan.truncated(), written[0] >= maxRecords));
    }

    /** Takes one exported record. */
    @FunctionalInterface
    public interface RecordSink {
        void write(Record record) throws IOException;
    }

    /** Takes one match; answers whether the scan should go on. */
    @FunctionalInterface
    private interface Visitor {
        boolean visit(Record record);
    }

    private record Scan(int scanned, boolean truncated) {
    }

    private static CompletableFuture<Scan> scan(Source source, Filter filter, int maxBuckets, Executor executor,
            Visitor visitor) {
        return source.list().thenComposeAsync(keys -> step(source, hours(keys, filter.from(), filter.to()), 0, 0,
                Math.max(1, maxBuckets), filter, executor, visitor), executor);
    }

    private static CompletableFuture<Scan> step(Source source, List<List<String>> hours, int index, int scanned,
            int maxBuckets, Filter filter, Executor executor, Visitor visitor) {
        if (index >= hours.size()) {
            return CompletableFuture.completedFuture(new Scan(scanned, false));
        }
        List<String> hour = hours.get(index);
        if (scanned + hour.size() > maxBuckets) {
            return CompletableFuture.completedFuture(new Scan(scanned, true));
        }
        List<CompletableFuture<JsonElement>> loads = hour.stream().map(source::load).toList();
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenComposeAsync(done -> {
            List<JsonElement> buckets = loads.stream().map(CompletableFuture::join).toList();
            for (Record record : merge(buckets, filter)) {
                if (!visitor.visit(record)) {
                    return CompletableFuture.completedFuture(new Scan(scanned + hour.size(), false));
                }
            }
            return step(source, hours, index + 1, scanned + hour.size(), maxBuckets, filter, executor, visitor);
        }, executor);
    }

    // ----- Formats --------------------------------------------------------------

    /** The CSV header line, ending in CRLF as RFC 4180 asks. */
    public static final String CSV_HEADER = "time_utc,epoch_ms,server,actor,action,target,detail\r\n";

    /** One record as a CSV line (RFC 4180), ending in CRLF. */
    public static String csvLine(Record record) {
        return String.join(",", csv(record.time()), Long.toString(record.at()), csv(record.server()),
                csv(record.actor()), csv(record.action()), csv(record.target()), csv(record.detail())) + "\r\n";
    }

    /**
     * Quotes a CSV field when it holds a comma, quote, CR or LF (doubling its
     * quotes), and also when it starts with {@code = + - @} or a tab, after an
     * apostrophe, so a spreadsheet never runs it as a formula.
     */
    public static String csv(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        return quote ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    /** Writes one record as a JSON object into an open array. */
    public static void json(JsonWriter writer, Record record) throws IOException {
        writer.beginObject();
        writer.name("time").value(Instant.ofEpochMilli(record.at()).toString());
        writer.name("at").value(record.at());
        writer.name("server").value(record.server());
        writer.name("actor").value(record.actor());
        writer.name("action").value(record.action());
        writer.name("target").value(record.target());
        writer.name("detail").value(record.detail());
        writer.endObject();
    }

    /** A JSON writer for an export: pretty-printed, HTML characters left alone. */
    public static JsonWriter jsonWriter(Writer out) {
        JsonWriter writer = new JsonWriter(out);
        writer.setIndent("  ");
        writer.setHtmlSafe(false);
        return writer;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
