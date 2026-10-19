package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle.State;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftStore;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;

import com.google.gson.Gson;

/**
 * Engine-free checks of the Phase 2 draft store (work package P2-A) over a temp
 * folder: a save/load round trip, a conflict when the file changed underneath,
 * refused ids and clones, archive moving the file out of {@code list()}, the
 * computed lifecycle going DRAFT → INVALID → READY → PUBLISHED, and the warning
 * about fields the model does not know (P2-D4).
 *
 * <p>The store's lookups are plain functions here: the "active revision" is a map
 * the check fills the way {@code LootBoxRegistry.publish} would, and the audit
 * trail and the registry's draft cache are recorders.</p>
 */
public final class LootBoxDraftStoreTest {

    private static final String KEY = "mystic:royal";
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final String STAMP = "20261019120000";
    private static final long TIMEOUT_SECONDS = 10;

    /** File work runs here, so the checks also see it never runs on the caller's thread. */
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "drafts-io");
        thread.setDaemon(true);
        return thread;
    });

    private LootBoxDraftStoreTest() {
    }

    public static void main(String[] args) throws IOException {
        try {
            saveLoadRoundTrip();
            conflictWhenTheFileChanged();
            badIdsAndClonesAreRefused();
            archiveMovesTheFileOutOfTheList();
            statusFollowsTheDraftAndTheActiveRevision();
            unknownFieldsAreReported();
            keyDraftsKeepTheirFiles();
        } finally {
            IO.shutdownNow();
        }
        System.out.println("LootBoxDraftStoreTest: all checks passed.");
    }

    // ----- Round trip ---------------------------------------------------------

    private static void saveLoadRoundTrip() throws IOException {
        try (Fixture f = new Fixture()) {
            String created = saved(await(f.pools.create("test_pool", "Test Pool", "alice")), "create");
            DraftStore.Loaded<LootBoxDraft> loaded = await(f.pools.load("test_pool")).orElseThrow();
            require(loaded.hash().equals(created), "load's hash differs from create's");
            require(loaded.hash().equals(sha256(loaded.path())), "the hash is not the file bytes' SHA-256");
            require(loaded.path().equals(f.poolDir.resolve("test_pool.json")), "created at " + loaded.path());
            require("Test Pool".equals(loaded.draft().displayName), "display name lost");

            LootBoxDraft edit = loaded.draft();
            edit.description = "A pool from the editor";
            edit.keys.add(KEY);
            edit.rewards.add(item("iron_sword", 3));
            String savedHash = saved(await(f.pools.save(edit, loaded.hash(), "alice")), "save");
            DraftStore.Loaded<LootBoxDraft> again = await(f.pools.load("test_pool")).orElseThrow();
            require(again.hash().equals(savedHash), "Saved's hash is not the written file's");
            require(Json.toString(again.draft()).equals(Json.toString(edit)), "the draft changed on the round trip");
            require(!again.hadUnknownFields(), "a file the store wrote has unknown fields");

            // The cache got the store's own copy, so the editor's later typing does not leak into it.
            edit.description = "typed after saving";
            require("A pool from the editor".equals(f.poolCache.get("test_pool").description),
                    "the cached draft is the caller's object");
            require(await(f.pools.list()).drafts().keySet().equals(Set.of("test_pool")), "list() after a save");

            f.requireAudit("DRAFT_CREATE", "test_pool", created);
            f.requireAudit("DRAFT_SAVE", "test_pool", savedHash);
            require(f.audit.stream().allMatch(entry -> entry.thread().startsWith("drafts-io")),
                    "file work ran on the caller's thread");
        }
    }

    // ----- Optimistic saves ---------------------------------------------------

    private static void conflictWhenTheFileChanged() throws IOException {
        try (Fixture f = new Fixture()) {
            saved(await(f.pools.create("test_pool", "Test Pool", "alice")), "create");
            DraftStore.Loaded<LootBoxDraft> alice = await(f.pools.load("test_pool")).orElseThrow();

            // Someone edits the file by hand while Alice has it open.
            Path file = alice.path();
            String handEdit = Files.readString(file).replace("Test Pool", "Hand Edited");
            Files.writeString(file, handEdit);
            String onDisk = sha256(file);

            alice.draft().description = "Alice's change";
            DraftStore.Result result = await(f.pools.save(alice.draft(), alice.hash(), "alice"));
            require(result instanceof DraftStore.Conflict conflict && onDisk.equals(conflict.currentHash()),
                    "a save over a changed file was " + result);
            require(Files.readString(file).equals(handEdit), "a conflicting save wrote to the file");

            DraftStore.Result fresh = await(f.pools.save(alice.draft(), null, "alice"));
            require(fresh instanceof DraftStore.Conflict, "a 'new' save over an existing draft was " + fresh);

            String overwritten = saved(await(f.pools.overwrite(alice.draft(), "alice")), "overwrite");
            require(Json.toString(load(f.pools, "test_pool")).equals(Json.toString(alice.draft())),
                    "overwrite did not write Alice's draft");
            f.requireAudit("DRAFT_OVERWRITE", "test_pool", overwritten + " replaced " + onDisk);

            // Two editors saving from the same load: one wins, the other is told.
            DraftStore.Loaded<LootBoxDraft> base = await(f.pools.load("test_pool")).orElseThrow();
            LootBoxDraft first = DraftLifecycle.freeze(base.draft(), LootBoxDraft.class);
            LootBoxDraft second = DraftLifecycle.freeze(base.draft(), LootBoxDraft.class);
            first.displayName = "First";
            second.displayName = "Second";
            CompletableFuture<DraftStore.Result> a = f.pools.save(first, base.hash(), "alice");
            CompletableFuture<DraftStore.Result> b = f.pools.save(second, base.hash(), "bob");
            List<DraftStore.Result> both = List.of(await(a), await(b));
            require(both.stream().filter(r -> r instanceof DraftStore.Saved).count() == 1
                    && both.stream().filter(r -> r instanceof DraftStore.Conflict).count() == 1,
                    "racing saves from one load gave " + both);
            String winner = load(f.pools, "test_pool").displayName;
            require(winner.equals(f.poolCache.get("test_pool").displayName), "the cache missed the winning save");
        }
    }

    // ----- Refusals -----------------------------------------------------------

    private static void badIdsAndClonesAreRefused() throws IOException {
        try (Fixture f = new Fixture()) {
            refused(await(f.pools.create("Bad Id", "Bad", "alice")), "[a-z0-9_]{3,48}", "a bad id");
            refused(await(f.pools.create("ab", "Short", "alice")), "[a-z0-9_]{3,48}", "a two-letter id");
            refused(await(f.pools.create("no_name", " ", "alice")), "display name", "a blank display name");
            LootBoxDraft bad = new LootBoxDraft();
            bad.id = "../escape";
            bad.displayName = "Escape";
            refused(await(f.pools.save(bad, null, "alice")), "[a-z0-9_]{3,48}", "saving a draft with a bad id");
            require(await(f.pools.load("../escape")).isEmpty(), "a bad id loaded something");

            saved(await(f.pools.create("source_pool", "Source", "alice")), "create source");
            saved(await(f.pools.create("taken_pool", "Taken", "alice")), "create target");
            refused(await(f.pools.create("taken_pool", "Again", "alice")), "already exists", "creating twice");
            String takenBefore = Files.readString(f.poolDir.resolve("taken_pool.json"));
            refused(await(f.pools.clone("source_pool", "taken_pool", "alice")), "already exists",
                    "a clone onto an existing id");
            require(Files.readString(f.poolDir.resolve("taken_pool.json")).equals(takenBefore),
                    "a refused clone changed the target");
            refused(await(f.pools.clone("missing_pool", "new_pool", "alice")), "no pool draft", "cloning nothing");
            refused(await(f.pools.clone("source_pool", "Bad Id", "alice")), "[a-z0-9_]{3,48}", "a clone to a bad id");

            // A published pool's id is not free just because its draft file is gone.
            f.active.put("live_pool", "content");
            refused(await(f.pools.create("live_pool", "Live", "alice")), "already published", "reusing a live id");
            refused(await(f.pools.clone("source_pool", "live_pool", "alice")), "already published",
                    "cloning onto a live id");

            LootBoxDraft source = load(f.pools, "source_pool");
            source.rewards.add(item("iron_sword", 3));
            source.keys.add(KEY);
            saved(await(f.pools.overwrite(source, "alice")), "fill source");
            String cloned = saved(await(f.pools.clone("source_pool", "copy_pool", "bob")), "clone");
            LootBoxDraft copy = load(f.pools, "copy_pool");
            require("copy_pool".equals(copy.id), "the clone kept the source id");
            copy.id = "source_pool";
            require(Json.toString(copy).equals(Json.toString(load(f.pools, "source_pool"))),
                    "the clone is not a whole copy");
            f.requireAudit("DRAFT_CLONE", "copy_pool", "from source_pool hash " + cloned);
            require(f.audit.stream().noneMatch(entry -> entry.target().equals("taken_pool")
                    && !entry.action().equals("DRAFT_CREATE")), "a refused change was audited");
        }
    }

    // ----- Archive ------------------------------------------------------------

    private static void archiveMovesTheFileOutOfTheList() throws IOException {
        try (Fixture f = new Fixture()) {
            saved(await(f.pools.create("old_pool", "Old", "alice")), "create");
            saved(await(f.pools.create("kept_pool", "Kept", "alice")), "create");
            String hash = await(f.pools.load("old_pool")).orElseThrow().hash();

            DraftStore.ArchiveResult result = await(f.pools.archive("old_pool", "alice"));
            Path expected = f.poolDir.resolve(DraftStore.ARCHIVE_DIR).resolve("old_pool-" + STAMP + ".json");
            require(result instanceof DraftStore.Archived archived && archived.path().equals(expected),
                    "archive gave " + result);
            require(!Files.exists(f.poolDir.resolve("old_pool.json")) && Files.exists(expected),
                    "the file was not moved under _archive/");
            require(sha256(expected).equals(hash), "the archived file is not the draft as it was");
            require(await(f.pools.list()).drafts().keySet().equals(Set.of("kept_pool")),
                    "an archived draft is still listed");
            require(await(f.pools.load("old_pool")).isEmpty(), "an archived draft still loads");
            require(!f.poolCache.containsKey("old_pool"), "the cache kept an archived draft");
            f.requireAudit("DRAFT_ARCHIVE", "old_pool", hash);

            // The id is free again, and archiving it twice in one second keeps both files.
            saved(await(f.pools.create("old_pool", "Old again", "alice")), "re-create");
            DraftStore.ArchiveResult second = await(f.pools.archive("old_pool", "alice"));
            require(second instanceof DraftStore.Archived archived
                    && archived.path().getFileName().toString().equals("old_pool-" + STAMP + "-2.json")
                    && Files.exists(expected), "a second archive replaced the first: " + second);
            List<DraftStore.Archived> listed = await(f.pools.archived());
            require(listed.size() == 2 && listed.stream().allMatch(a -> a.id().equals("old_pool")
                    && a.status().state() == State.ARCHIVED), "archive listing: " + listed);
            refused(await(f.pools.archive("old_pool", "alice")), "no pool draft", "archiving nothing");
        }
    }

    // ----- Lifecycle ----------------------------------------------------------

    private static void statusFollowsTheDraftAndTheActiveRevision() throws IOException {
        try (Fixture f = new Fixture()) {
            require(await(f.pools.status("test_pool")).isEmpty(), "a missing draft has a status");
            saved(await(f.pools.create("test_pool", "Test Pool", "alice")), "create");
            requireState(f, "test_pool", State.DRAFT, "a new pool");

            LootBoxDraft draft = load(f.pools, "test_pool");
            draft.rewards.add(item("iron_sword", 3));
            draft.rewards.add(item("missing_thing", 1));
            draft.rewards.get(1).item.itemId = "Missing_Item";
            save(f, draft);
            DraftLifecycle.Status invalid = requireState(f, "test_pool", State.INVALID, "a pool with problems");
            require(invalid.problems().size() == 2 && invalid.label().equals("INVALID (2 problems)"),
                    "problems: " + invalid.problems());

            draft = load(f.pools, "test_pool");
            draft.keys.add(KEY);
            draft.rewards.remove(1);
            save(f, draft);
            requireState(f, "test_pool", State.READY, "a valid, never published pool");

            f.publish(load(f.pools, "test_pool"));
            requireState(f, "test_pool", State.PUBLISHED, "the pool as published");

            // Reformatting the file is not a change; the spec is compared, not the bytes.
            Path file = f.poolDir.resolve("test_pool.json");
            String before = sha256(file);
            Files.writeString(file, new Gson().toJson(load(f.pools, "test_pool")));
            require(!sha256(file).equals(before), "the file was not reformatted");
            requireState(f, "test_pool", State.PUBLISHED, "a reformatted published pool");

            draft = load(f.pools, "test_pool");
            draft.rewards.get(0).weight = 7;
            saved(await(f.pools.overwrite(draft, "alice")), "edit");
            requireState(f, "test_pool", State.READY, "an edited published pool");

            draft = load(f.pools, "test_pool");
            draft.rewards.clear();
            saved(await(f.pools.overwrite(draft, "alice")), "empty");
            requireState(f, "test_pool", State.INVALID, "an emptied published pool");

            f.disabled.add("test_pool");
            DraftLifecycle.Status closed = requireState(f, "test_pool", State.DISABLED, "a closed pool");
            require(!closed.problems().isEmpty(), "DISABLED hid the draft's problems");

            // The editor's working copy needs no file at all.
            LootBoxDraft working = load(f.pools, "test_pool");
            working.id = "unsaved_pool";
            require(f.pools.status(working).state() == State.DRAFT, "an unsaved empty copy is not DRAFT");
        }
    }

    // ----- Unknown fields (P2-D4) ---------------------------------------------

    private static void unknownFieldsAreReported() throws IOException {
        try (Fixture f = new Fixture()) {
            Files.createDirectories(f.poolDir);
            Files.writeString(f.poolDir.resolve("hand.json"), """
                    {
                      "id": "hand_pool",
                      "displayName": "Hand Written",
                      "colour": "gold",
                      "keys": ["mystic:royal"],
                      "rewards": [
                        { "id": "a", "name": "A", "type": "ITEM", "weight": 1, "glow": true,
                          "item": { "itemId": "Ingredient_Bar_Iron", "quantity": 1, "nbt": "x" } }
                      ]
                    }
                    """);
            DraftStore.Loaded<LootBoxDraft> loaded = await(f.pools.load("hand_pool")).orElseThrow();
            require(loaded.hadUnknownFields()
                    && loaded.unknownFields().equals(List.of("colour", "rewards[0].glow", "rewards[0].item.nbt")),
                    "unknown fields: " + loaded.unknownFields());
            require(await(f.pools.list()).drafts().get("hand_pool").hadUnknownFields(), "list() lost the warning");

            saved(await(f.pools.save(loaded.draft(), loaded.hash(), "alice")), "save");
            DraftStore.Loaded<LootBoxDraft> after = await(f.pools.load("hand_pool")).orElseThrow();
            require(after.path().getFileName().toString().equals("hand.json"), "the save moved the file");
            require(!after.hadUnknownFields() && !Files.readString(after.path()).contains("colour"),
                    "a save kept fields the model does not know");
        }
    }

    // ----- Keys ---------------------------------------------------------------

    private static void keyDraftsKeepTheirFiles() throws IOException {
        try (Fixture f = new Fixture()) {
            Files.createDirectories(f.keyDir);
            Files.writeString(f.keyDir.resolve("example_key.json"), """
                    { "id": "example_key", "baseItemId": "Ingredient_Bar_Copper", "name": "Example",
                      "lore": [], "uses": 1, "expiresAfterDays": 0 }
                    """);
            DraftStore.Loaded<KeyDraft> example = await(f.keys.load("Example_Key")).orElseThrow();
            require("mystic:example_key".equals(example.draft().id), "the key id was not normalised");
            example.draft().uses = 3;
            saved(await(f.keys.save(example.draft(), example.hash(), "alice")), "save key");
            try (Stream<Path> files = Files.list(f.keyDir)) {
                require(files.map(p -> p.getFileName().toString()).toList().equals(List.of("example_key.json")),
                        "a key save wrote a second file");
            }
            require(f.keyCache.get("mystic:example_key").uses == 3, "the key cache missed the save");

            saved(await(f.keys.create("Royal", "Royal Key", "alice")), "create key");
            require(Files.exists(f.keyDir.resolve("mystic.royal.json")), "a new key's file is not namespace.name.json");
            require(f.keys.status(load(f.keys, KEY)).state() == State.DRAFT, "a new key is not DRAFT");
            KeyDraft royal = load(f.keys, KEY);
            royal.baseItemId = "Ingredient_Bar_Iron";
            saved(await(f.keys.overwrite(royal, "alice")), "fill key");
            require(f.keys.status(load(f.keys, KEY)).state() == State.READY, "a valid key is not READY");
            f.publishedKeys.put(KEY, DraftLifecycle.freeze(load(f.keys, KEY), KeyDraft.class));
            require(f.keys.status(load(f.keys, KEY)).state() == State.PUBLISHED, "a published key is not PUBLISHED");
            refused(await(f.keys.create("bad key", "Bad", "alice")), "namespace:name", "a bad key id");
            refused(await(f.keys.clone("royal", "royal", "alice")), "already exists", "cloning a key onto itself");
        }
    }

    // ----- Fixture ------------------------------------------------------------

    private record Entry(String actor, String action, String target, String detail, String thread) {
    }

    /** A pool store and a key store over a temp folder, with recorders standing in for the registry. */
    private static final class Fixture implements AutoCloseable {

        final Path root = Files.createTempDirectory("lootbox-drafts");
        final Path poolDir = root.resolve("lootboxes");
        final Path keyDir = root.resolve("keys");
        final List<Entry> audit = new CopyOnWriteArrayList<>();
        final Map<String, String> active = new ConcurrentHashMap<>();
        final Set<String> disabled = ConcurrentHashMap.newKeySet();
        final Map<String, KeyDraft> publishedKeys = new ConcurrentHashMap<>();
        final Map<String, LootBoxDraft> poolCache = new ConcurrentHashMap<>();
        final Map<String, KeyDraft> keyCache = new ConcurrentHashMap<>();
        final DraftStore<LootBoxDraft> pools;
        final DraftStore<KeyDraft> keys;

        Fixture() throws IOException {
            DraftValidator.Environment env = new DraftValidator.Environment(item -> !item.startsWith("Missing"),
                    KEY::equals, Map.of(), true, true, true);
            DraftStore.Auditor auditor = (actor, action, target, detail) -> audit.add(
                    new Entry(actor, action, target, detail, Thread.currentThread().getName()));
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            pools = new DraftStore<>(DraftStore.POOLS, poolDir,
                    DraftLifecycle.pools(() -> env, active::get, disabled::contains), auditor, cache(poolCache), IO,
                    clock);
            keys = new DraftStore<>(DraftStore.KEYS, keyDir, DraftLifecycle.keys(item -> true, publishedKeys::get),
                    auditor, cache(keyCache), IO, clock);
        }

        /** What {@code LootBoxRegistry.publish} stores as the active revision's content hash. */
        void publish(LootBoxDraft draft) {
            LootBoxDraft frozen = DraftLifecycle.freeze(draft, LootBoxDraft.class);
            active.put(draft.id, DraftLifecycle.poolContentHash(frozen, OddsEngine.resolveWeights(frozen)));
        }

        void requireAudit(String action, String target, String detailFragment) {
            require(audit.stream().anyMatch(entry -> entry.action().equals(action) && entry.target().equals(target)
                    && entry.detail().contains(detailFragment)), "no " + action + " audit for " + target
                    + " with '" + detailFragment + "' in " + audit);
        }

        @Override
        public void close() throws IOException {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }

        private static <T> DraftStore.Listener<T> cache(Map<String, T> cache) {
            return new DraftStore.Listener<>() {
                @Override
                public void changed(String id, T draft) {
                    if (draft == null) {
                        cache.remove(id);
                    } else {
                        cache.put(id, draft);
                    }
                }

                @Override
                public void listed(DraftStore.Listing<T> listing) {
                    cache.clear();
                    listing.drafts().forEach((id, loaded) -> cache.put(id, loaded.draft()));
                }
            };
        }
    }

    // ----- Helpers ------------------------------------------------------------

    private static DraftLifecycle.Status requireState(Fixture f, String id, State state, String what) {
        DraftLifecycle.Status status = await(f.pools.status(id)).orElseThrow();
        require(status.state() == state, what + " is " + status.label() + " " + status.problems() + ", not " + state);
        LootBoxDraft cached = f.poolCache.get(id);
        require(cached != null && f.pools.status(cached).state() == state, what + ": the cached draft disagrees");
        return status;
    }

    private static void save(Fixture f, LootBoxDraft draft) {
        String hash = await(f.pools.load(draft.id)).orElseThrow().hash();
        saved(await(f.pools.save(draft, hash, "alice")), "save " + draft.id);
    }

    private static <T> T load(DraftStore<T> store, String id) {
        return await(store.load(id)).orElseThrow(() -> new AssertionError("no draft '" + id + "'")).draft();
    }

    private static LootBoxDraft.RewardSpec item(String id, long weight) {
        LootBoxDraft.RewardSpec reward = new LootBoxDraft.RewardSpec();
        reward.id = id;
        reward.name = id;
        reward.type = "ITEM";
        reward.weight = weight;
        reward.item = new LootBoxDraft.ItemSpec();
        reward.item.itemId = "Ingredient_Bar_Iron";
        return reward;
    }

    private static String saved(DraftStore.Result result, String what) {
        if (result instanceof DraftStore.Saved saved) {
            return saved.hash();
        }
        throw new AssertionError(what + " was " + result);
    }

    private static void refused(Object result, String fragment, String what) {
        require(result instanceof DraftStore.Refused refused && refused.reason().contains(fragment),
                what + " was " + result + ", not refused with '" + fragment + "'");
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("a draft store call failed: " + e, e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
