package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The draft files of one kind (pools in {@code lootboxes/}, keys in {@code keys/}),
 * for the in-game editor (Phase 2, decisions P2-D1, P2-D2 and P2-D4). Publishing
 * stays the only way into storage; nothing here touches it.
 *
 * <p>A draft is found by the id inside it, so hand-named files keep working, and
 * only the folder's own {@code *.json} files count: {@code _archive/} is never
 * read as drafts. Every method returns at once and does its file work on the
 * executor, never on the calling thread. Changes are serialised per store, so a
 * conflict check and the write after it cannot interleave with another change.</p>
 *
 * <p>Saves are optimistic (P2-D2): a caller passes the hash it loaded, and
 * {@link #save} answers {@link Conflict} when the file changed since. A hash is the
 * lowercase hex SHA-256 of the file's bytes; treat it as opaque. Gson drops fields
 * the model does not know when a draft is saved (P2-D4), so {@link Loaded} names
 * them for the editor to warn about once.</p>
 */
public final class DraftStore<T> {

    public static final String ARCHIVE_DIR = "_archive";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneOffset.UTC);

    /** What differs between pool and key drafts. */
    public record Kind<T>(
            String noun,
            Class<T> type,
            Pattern idPattern,
            String idRule,
            UnaryOperator<String> normaliseId,
            Function<T, String> idOf,
            BiConsumer<T, String> setId,
            BiFunction<String, String, T> blank,
            UnaryOperator<String> fileBase) {
    }

    public static final Kind<LootBoxDraft> POOLS = new Kind<>("pool", LootBoxDraft.class,
            DraftValidator.LOOTBOX_ID, "must match [a-z0-9_]{3,48}", id -> id,
            draft -> draft.id, (draft, id) -> draft.id = id,
            (id, displayName) -> {
                LootBoxDraft draft = new LootBoxDraft();
                draft.id = id;
                draft.displayName = displayName;
                return draft;
            },
            id -> id);

    public static final Kind<KeyDraft> KEYS = new Kind<>("key", KeyDraft.class,
            DraftValidator.KEY_ID, "must look like namespace:name ([a-z0-9_])", DraftValidator::normaliseKeyId,
            key -> key.id, (key, id) -> key.id = id,
            (id, displayName) -> {
                KeyDraft key = new KeyDraft();
                key.id = id;
                key.baseItemId = "";
                key.name = displayName;
                return key;
            },
            id -> id.replace(':', '.'));

    /** One draft as read from disk. */
    public record Loaded<T>(T draft, String hash, Path path, List<String> unknownFields) {

        public Loaded {
            unknownFields = List.copyOf(unknownFields);
        }

        /** The file had fields the model does not know; the first in-game save drops them (P2-D4). */
        public boolean hadUnknownFields() {
            return !unknownFields.isEmpty();
        }
    }

    /** Every draft in the folder by id, in file order, and the files that could not be used. */
    public record Listing<T>(Map<String, Loaded<T>> drafts, List<String> errors) {
    }

    /** Outcome of a save, overwrite, create or clone. */
    public sealed interface Result permits Saved, Conflict, Refused {
    }

    /** Outcome of an archive. */
    public sealed interface ArchiveResult permits Archived, Refused {
    }

    /** Written; {@code hash} is the new file's, for the next save. */
    public record Saved(String hash) implements Result {
    }

    /** The file changed since it was loaded ({@code currentHash} is {@code null} when it is gone). Nothing was written. */
    public record Conflict(String currentHash) implements Result {
    }

    /** Not done, for a reason a player can read. Nothing was written. */
    public record Refused(String reason) implements Result, ArchiveResult {
    }

    /** A draft under {@code _archive/}: the result of {@link #archive} and an entry of {@link #archived()}. */
    public record Archived(String id, Path path) implements ArchiveResult {

        public DraftLifecycle.Status status() {
            return new DraftLifecycle.Status(DraftLifecycle.State.ARCHIVED, List.of());
        }
    }

    /** Records one audit entry; {@link AuditService#record} in the module. */
    @FunctionalInterface
    public interface Auditor {
        void record(String actor, String action, String target, String detail);
    }

    /**
     * Told about every change, while the store still holds its lock, so a cache
     * fed from here sees changes in the order they reached the disk.
     */
    public interface Listener<T> {

        /** A draft was written ({@code draft} is the store's own copy) or archived ({@code draft} is null). */
        void changed(String id, T draft);

        /** The folder was read in full; replace whatever was cached. */
        void listed(Listing<T> listing);
    }

    private final Kind<T> kind;
    private final Path folder;
    private final DraftLifecycle<T> lifecycle;
    private final Auditor audit;
    private final Listener<T> listener;
    private final Executor executor;
    private final Clock clock;
    private final Object lock = new Object();

    public DraftStore(Kind<T> kind, Path folder, DraftLifecycle<T> lifecycle, Auditor audit, Listener<T> listener,
            Executor executor) {
        this(kind, folder, lifecycle, audit, listener, executor, Clock.systemUTC());
    }

    public DraftStore(Kind<T> kind, Path folder, DraftLifecycle<T> lifecycle, Auditor audit, Listener<T> listener,
            Executor executor, Clock clock) {
        this.kind = kind;
        this.folder = folder;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.listener = listener;
        this.executor = executor;
        this.clock = clock;
    }

    public Kind<T> kind() {
        return kind;
    }

    public Path folder() {
        return folder;
    }

    // ----- Reading ------------------------------------------------------------

    /** Reads every draft. Unreadable files, files without an id and repeated ids are listed as errors. */
    public CompletableFuture<Listing<T>> list() {
        return async(() -> {
            synchronized (lock) {
                List<String> errors = new ArrayList<>();
                Map<String, Loaded<T>> drafts = new LinkedHashMap<>();
                for (Path file : files(folder, errors)) {
                    try {
                        Loaded<T> loaded = read(file);
                        String id = kind.idOf().apply(loaded.draft());
                        if (id == null) {
                            errors.add(file.getFileName() + ": no id");
                        } else if (drafts.putIfAbsent(id, loaded) != null) {
                            errors.add(file.getFileName() + ": duplicate " + kind.noun() + " id '" + id + "'");
                        }
                    } catch (IOException | RuntimeException e) {
                        errors.add(file.getFileName() + ": " + e.getMessage());
                    }
                }
                Listing<T> listing = new Listing<>(Collections.unmodifiableMap(drafts), List.copyOf(errors));
                listener.listed(listing);
                return listing;
            }
        });
    }

    /** @return the draft with this id, or empty when there is none (or the id is malformed). */
    public CompletableFuture<Optional<Loaded<T>>> load(String rawId) {
        String id = kind.normaliseId().apply(rawId);
        return async(() -> {
            Path file = idProblem(id) == null ? locate(id) : null;
            return file == null ? Optional.empty() : Optional.of(read(file));
        });
    }

    /** Drafts moved to {@code _archive/}, oldest file name first. Unreadable files are skipped. */
    public CompletableFuture<List<Archived>> archived() {
        return async(() -> {
            List<Archived> archived = new ArrayList<>();
            for (Path file : files(folder.resolve(ARCHIVE_DIR), new ArrayList<>())) {
                String id = idIn(file);
                if (id != null) {
                    archived.add(new Archived(id, file));
                }
            }
            return archived;
        });
    }

    /** The draft's lifecycle state; empty when there is no draft with this id. */
    public CompletableFuture<Optional<DraftLifecycle.Status>> status(String rawId) {
        return load(rawId).thenApply(loaded -> loaded.map(found -> status(found.draft())));
    }

    /** The lifecycle state of a draft in hand, such as an editor's working copy. No file work. */
    public DraftLifecycle.Status status(T draft) {
        return lifecycle.status(kind.normaliseId().apply(kind.idOf().apply(draft)), draft);
    }

    // ----- Writing ------------------------------------------------------------

    /**
     * Writes {@code draft} if its file still has {@code expectedHash}, the hash it was
     * loaded with; {@code null} means the draft is new and no file may hold its id yet.
     */
    public CompletableFuture<Result> save(T draft, String expectedHash, String actor) {
        return async(() -> {
            synchronized (lock) {
                return write(draft, expectedHash, false, actor);
            }
        });
    }

    /** Writes {@code draft} whatever the file holds now; the answer to a {@link Conflict}. Audited as an overwrite. */
    public CompletableFuture<Result> overwrite(T draft, String actor) {
        return async(() -> {
            synchronized (lock) {
                return write(draft, null, true, actor);
            }
        });
    }

    /** Writes a new, empty draft. Refuses an id that already has a draft or a published version. */
    public CompletableFuture<Result> create(String rawId, String displayName, String actor) {
        String id = kind.normaliseId().apply(rawId);
        return async(() -> {
            synchronized (lock) {
                String problem = newIdProblem(id);
                if (problem != null) {
                    return new Refused(problem);
                }
                if (displayName == null || displayName.isBlank()) {
                    return new Refused("the display name is empty");
                }
                T draft = kind.blank().apply(id, displayName.trim());
                return writeNew(draft, id, "DRAFT_CREATE", "", actor);
            }
        });
    }

    /**
     * Copies the whole of draft {@code sourceId} to a new draft {@code newId}. Only the
     * draft file is copied; nothing comes from its published revisions.
     */
    public CompletableFuture<Result> clone(String sourceId, String newId, String actor) {
        String from = kind.normaliseId().apply(sourceId);
        String to = kind.normaliseId().apply(newId);
        return async(() -> {
            synchronized (lock) {
                String problem = newIdProblem(to);
                if (problem != null) {
                    return new Refused(problem);
                }
                Path source = idProblem(from) == null ? locate(from) : null;
                if (source == null) {
                    return new Refused("there is no " + kind.noun() + " draft '" + from + "'");
                }
                T copy = read(source).draft();
                kind.setId().accept(copy, to);
                return writeNew(copy, to, "DRAFT_CLONE", "from " + from + " ", actor);
            }
        });
    }

    /**
     * Moves the draft's file to {@code _archive/<id>-<yyyyMMddHHmmss>.json} (UTC). It is
     * never deleted; moving the file back restores it. Published revisions are untouched.
     */
    public CompletableFuture<ArchiveResult> archive(String rawId, String actor) {
        String id = kind.normaliseId().apply(rawId);
        return async(() -> {
            synchronized (lock) {
                Path source = idProblem(id) == null ? locate(id) : null;
                if (source == null) {
                    return new Refused("there is no " + kind.noun() + " draft '" + id + "'");
                }
                String hash = sha256(Files.readAllBytes(source));
                Path dir = folder.resolve(ARCHIVE_DIR);
                Files.createDirectories(dir);
                String stem = kind.fileBase().apply(id) + "-" + STAMP.format(clock.instant());
                Path target = dir.resolve(stem + ".json");
                for (int n = 2; Files.exists(target); n++) {
                    target = dir.resolve(stem + "-" + n + ".json");
                }
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(source, target);
                }
                audit.record(actor, "DRAFT_ARCHIVE", id,
                        "hash " + hash + " to " + ARCHIVE_DIR + "/" + target.getFileName());
                listener.changed(id, null);
                return new Archived(id, target);
            }
        });
    }

    private Result write(T draft, String expectedHash, boolean force, String actor) throws IOException {
        if (draft == null) {
            return new Refused("the draft is empty");
        }
        String id = kind.normaliseId().apply(kind.idOf().apply(draft));
        String problem = idProblem(id);
        if (problem != null) {
            return new Refused(problem);
        }
        Path current = locate(id);
        String currentHash = current == null ? null : sha256(Files.readAllBytes(current));
        if (!force && !Objects.equals(expectedHash, currentHash)) {
            return new Conflict(currentHash);
        }
        Path target = current != null ? current : freshFile(id);
        if (target == null) {
            return new Refused(fileTaken(id));
        }
        T copy;
        try {
            copy = DraftLifecycle.freeze(draft, kind.type());
        } catch (RuntimeException e) {
            // Gson refuses NaN and infinite numbers.
            return new Refused("the draft cannot be written as JSON: " + e.getMessage());
        }
        kind.setId().accept(copy, id);
        String hash = writeFile(target, copy);
        if (force) {
            audit.record(actor, "DRAFT_OVERWRITE", id,
                    "hash " + hash + " replaced " + (currentHash == null ? "no file" : currentHash));
        } else {
            audit.record(actor, "DRAFT_SAVE", id, "hash " + hash + (current == null ? " (new file)" : ""));
        }
        listener.changed(id, copy);
        return new Saved(hash);
    }

    private Result writeNew(T draft, String id, String action, String detail, String actor) throws IOException {
        Path target = freshFile(id);
        if (target == null) {
            return new Refused(fileTaken(id));
        }
        String hash = writeFile(target, draft);
        audit.record(actor, action, id, detail + "hash " + hash);
        listener.changed(id, draft);
        return new Saved(hash);
    }

    /** Writes through {@link Json#writeFile} (temp file, then an atomic move) and returns the bytes' hash. */
    private static String writeFile(Path file, Object draft) throws IOException {
        String hash = sha256(Json.toString(draft).getBytes(StandardCharsets.UTF_8));
        Json.writeFile(file, draft);
        return hash;
    }

    // ----- Files and ids --------------------------------------------------------

    private String idProblem(String id) {
        return id == null || !kind.idPattern().matcher(id).matches()
                ? kind.noun() + " id '" + id + "' " + kind.idRule()
                : null;
    }

    private String newIdProblem(String id) throws IOException {
        String problem = idProblem(id);
        if (problem != null) {
            return problem;
        }
        if (locate(id) != null) {
            return "a " + kind.noun() + " draft '" + id + "' already exists";
        }
        if (lifecycle.publishedHash().apply(id) != null) {
            return kind.noun() + " '" + id + "' is already published; edit its draft or pick another id";
        }
        return null;
    }

    /** The file whose draft has this id, the first in file order as {@link #list()} picks it, or null. */
    private Path locate(String id) throws IOException {
        for (Path file : files(folder, null)) {
            if (id.equals(idIn(file))) {
                return file;
            }
        }
        return null;
    }

    /** {@code <id>.json} for a new draft, or null when a file of that name holds something else. */
    private Path freshFile(String id) {
        Path file = folder.resolve(kind.fileBase().apply(id) + ".json");
        return Files.exists(file) ? null : file;
    }

    private String fileTaken(String id) {
        return folder.getFileName() + "/" + kind.fileBase().apply(id) + ".json exists but does not hold "
                + kind.noun() + " '" + id + "'; rename or fix that file first";
    }

    private Loaded<T> read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        JsonElement tree = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        T draft = Json.fromJson(tree, kind.type());
        if (draft == null) {
            throw new IOException("the file is empty");
        }
        String id = kind.idOf().apply(draft);
        if (id != null) {
            kind.setId().accept(draft, kind.normaliseId().apply(id));
        }
        List<String> unknown = new ArrayList<>();
        unknownFields(tree, Json.toTree(draft), "", unknown);
        return new Loaded<>(draft, sha256(bytes), file, unknown);
    }

    /** The normalised id inside a draft file, or null when it has none or cannot be read. */
    private String idIn(Path file) {
        try {
            JsonElement id = Json.asObject(Json.parse(Files.readString(file, StandardCharsets.UTF_8))).get("id");
            return id != null && id.isJsonPrimitive() ? kind.normaliseId().apply(id.getAsString()) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Names every field of {@code source} that has no counterpart in {@code model}, the
     * model read back as JSON. Nulls are serialised, so every known field is there.
     */
    private static void unknownFields(JsonElement source, JsonElement model, String path, List<String> unknown) {
        if (source instanceof JsonObject object) {
            JsonObject known = model instanceof JsonObject m ? m : new JsonObject();
            for (Map.Entry<String, JsonElement> field : object.entrySet()) {
                String name = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
                if (!known.has(field.getKey())) {
                    unknown.add(name);
                } else {
                    unknownFields(field.getValue(), known.get(field.getKey()), name, unknown);
                }
            }
        } else if (source instanceof JsonArray array && model instanceof JsonArray known) {
            for (int i = 0; i < array.size() && i < known.size(); i++) {
                unknownFields(array.get(i), known.get(i), path + "[" + i + "]", unknown);
            }
        }
    }

    /** The folder's own {@code *.json} files, sorted; subfolders such as {@code _archive/} are not entered. */
    private static List<Path> files(Path dir, List<String> errors) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json") && Files.isRegularFile(file))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            if (errors == null) {
                throw e;
            }
            errors.add(dir.getFileName() + ": " + e.getMessage());
            return List.of();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @FunctionalInterface
    private interface Io<R> {
        R run() throws IOException;
    }

    private <R> CompletableFuture<R> async(Io<R> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.run();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }
}
