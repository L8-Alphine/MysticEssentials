package org.hyzionstudios.mysticessentials.modules.lootbox.storage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PendingInbox;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.NetworkRules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Document IO for LootBox over the Core {@link StorageService}, so every provider
 * (JSON, MySQL, MariaDB) works. One namespace per document type; keys are kept
 * filesystem-safe because the JSON provider uses them as file names (key ids'
 * {@code :} becomes {@code .}).
 *
 * <p>Every read-modify-write goes through {@link StorageService#updateShared}: on
 * one server it runs after every earlier operation on the same key, and on the
 * shared SQL provider it is one transaction holding the row lock, so the
 * single-document commits of the opening saga are atomic across servers too
 * (P4-WP1).</p>
 *
 * <p>While this server holds a player's network lease (P4-WP2), every write to
 * that player's documents (player state, openings, pending inbox) carries the
 * lease's fencing token and is refused when the document saw a newer one.</p>
 */
public final class LootBoxStorage {

    static final String DEF_NS = "lootbox_def";
    static final String REV_NS = "lootbox_rev";
    static final String KEYDEF_NS = "lootbox_keydef";
    static final String SERIAL_NS = "lootbox_serial";
    static final String PLAYER_NS = "lootbox_player";
    static final String OPEN_NS = "lootbox_open";
    static final String PENDING_NS = "lootbox_pending";
    static final String CRATE_NS = "lootbox_crate";
    static final String AUDIT_NS = "lootbox_audit";
    static final String META_NS = "lootbox_meta";
    static final String STATS_NS = "lootbox_stats";
    static final String STATS_PLAYER_NS = "lootbox_stats_player";

    private final Supplier<StorageService> storage;
    /** The fencing token of the lease this server holds for a player, or 0 (P4-WP2). */
    private volatile Function<UUID, Long> fences = player -> 0L;

    public LootBoxStorage(MysticCore core) {
        this(core::getStorageService);
    }

    /** Over any {@link StorageService}, looked up on every call; the saga checks pass an in-memory one. */
    public LootBoxStorage(Supplier<StorageService> storage) {
        this.storage = storage;
    }

    private StorageService storage() {
        return storage.get();
    }

    /** Where the fencing tokens of held network leases come from (P4-WP2). */
    public void fences(Function<UUID, Long> source) {
        this.fences = source == null ? player -> 0L : source;
    }

    /** Whether updates are atomic across every server (the shared SQL provider). */
    public boolean sharedAcrossServers() {
        return storage().sharedAcrossServers();
    }

    private long fenceOf(UUID player) {
        Long fence = player == null ? null : fences.apply(player);
        return fence == null ? 0L : fence;
    }

    private static UUID uuidOrNull(String player) {
        try {
            return player == null ? null : UUID.fromString(player);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ----- Network (P4-WP1/WP2) -----------------------------------------------

    /** The next fencing token: one shared counter, incremented atomically. */
    public CompletableFuture<Long> nextFence() {
        return storage().updateShared(META_NS, "fence", element -> {
            long current = element != null && element.isJsonPrimitive() ? element.getAsLong() : 0L;
            return new JsonPrimitive(current + 1);
        }).thenApply(JsonElement::getAsLong);
    }

    /**
     * Records {@code fingerprint} as the network's key-signing secret when none is
     * recorded, and returns the recorded one. Only a fingerprint is stored, never
     * the secret.
     */
    public CompletableFuture<String> claimSecretFingerprint(String fingerprint) {
        return storage().updateShared(META_NS, "secret", element -> element != null && element.isJsonPrimitive()
                ? null : new JsonPrimitive(fingerprint))
                .thenApply(stored -> stored == null ? null : stored.getAsString());
    }

    /** {@code mystic:royal_key} → {@code mystic.royal_key}. */
    public static String keyIdKey(String keyId) {
        return keyId.replace(':', '.');
    }

    static String revisionKey(String lootboxId, int revision) {
        return lootboxId + "@" + revision;
    }

    // ----- Pools ----------------------------------------------------------------

    public CompletableFuture<LootBoxRevision.State> loadState(String lootboxId) {
        return load(DEF_NS, lootboxId, LootBoxRevision.State.class);
    }

    public CompletableFuture<LootBoxRevision.State> updateState(String lootboxId,
            UnaryOperator<LootBoxRevision.State> change) {
        return update(DEF_NS, lootboxId, LootBoxRevision.State.class, change);
    }

    public CompletableFuture<List<String>> listStates() {
        return storage().listKeys(DEF_NS);
    }

    public CompletableFuture<LootBoxRevision> loadRevision(String lootboxId, int revision) {
        return load(REV_NS, revisionKey(lootboxId, revision), LootBoxRevision.class);
    }

    /** Revisions are written once and never rewritten. */
    public CompletableFuture<Void> saveRevision(LootBoxRevision revision) {
        return storage().save(REV_NS, revisionKey(revision.lootboxId, revision.revision), Json.toTree(revision));
    }

    // ----- Keys -----------------------------------------------------------------

    public CompletableFuture<KeyDraft.Published> loadKeyDefinition(String keyId) {
        return load(KEYDEF_NS, keyIdKey(keyId), KeyDraft.Published.class);
    }

    public CompletableFuture<KeyDraft.Published> updateKeyDefinition(String keyId,
            UnaryOperator<KeyDraft.Published> change) {
        return update(KEYDEF_NS, keyIdKey(keyId), KeyDraft.Published.class, change);
    }

    public CompletableFuture<List<String>> listKeyDefinitions() {
        return storage().listKeys(KEYDEF_NS);
    }

    public CompletableFuture<KeySerial> loadSerial(String serial) {
        return load(SERIAL_NS, serial, KeySerial.class);
    }

    public CompletableFuture<Void> saveSerial(KeySerial serial) {
        return storage().save(SERIAL_NS, serial.serial, Json.toTree(serial));
    }

    /** Atomic per serial: the commit point of physical-key openings. */
    public CompletableFuture<KeySerial> updateSerial(String serial, UnaryOperator<KeySerial> change) {
        return update(SERIAL_NS, serial, KeySerial.class, change);
    }

    // ----- Players ------------------------------------------------------------

    public CompletableFuture<PlayerLootState> loadPlayer(UUID player) {
        return load(PLAYER_NS, player.toString(), PlayerLootState.class)
                .thenApply(state -> state == null ? new PlayerLootState() : state);
    }

    /**
     * Atomic per player: the commit point of virtual-key openings. {@code change}
     * gets a fresh state if none is stored. Fenced while a network lease is held.
     */
    public CompletableFuture<PlayerLootState> updatePlayer(UUID player, UnaryOperator<PlayerLootState> change) {
        long lease = fenceOf(player);
        return update(PLAYER_NS, player.toString(), PlayerLootState.class, state -> {
            PlayerLootState current = state == null ? new PlayerLootState() : state;
            NetworkRules.admit(current.fence, lease);
            PlayerLootState next = change.apply(current);
            if (next != null) {
                next.fence = NetworkRules.after(current.fence, lease);
            }
            return next;
        });
    }

    // ----- Openings -----------------------------------------------------------

    public CompletableFuture<Opening> loadOpening(String openingId) {
        return load(OPEN_NS, openingId, Opening.class);
    }

    /** Writes the whole opening; fenced (as a read-modify-write) while its player's network lease is held. */
    public CompletableFuture<Void> saveOpening(Opening opening) {
        long lease = fenceOf(uuidOrNull(opening.player));
        if (lease <= 0) {
            return storage().save(OPEN_NS, opening.openingId, Json.toTree(opening));
        }
        return update(OPEN_NS, opening.openingId, Opening.class, stored -> {
            long seen = stored == null ? 0L : stored.fence;
            NetworkRules.admit(seen, lease);
            opening.fence = NetworkRules.after(seen, lease);
            return opening;
        }).thenApply(saved -> null);
    }

    public CompletableFuture<Opening> updateOpening(String openingId, UnaryOperator<Opening> change) {
        return update(OPEN_NS, openingId, Opening.class, stored -> {
            long lease = stored == null ? 0L : fenceOf(uuidOrNull(stored.player));
            if (stored != null) {
                NetworkRules.admit(stored.fence, lease);
            }
            Opening next = change.apply(stored);
            if (next != null && stored != null) {
                next.fence = NetworkRules.after(stored.fence, lease);
            }
            return next;
        });
    }

    /** Every stored opening id; the retention sweep (P4-WP5) walks them. */
    public CompletableFuture<List<String>> listOpenings() {
        return storage().listKeys(OPEN_NS);
    }

    public CompletableFuture<Boolean> deleteOpening(String openingId) {
        return storage().delete(OPEN_NS, openingId);
    }

    // ----- Statistics (P4-WP4) -------------------------------------------------

    public CompletableFuture<JsonElement> loadStats(String bucket) {
        return storage().load(STATS_NS, bucket);
    }

    /** One atomic update of a day's counts; shared across servers like every other update. */
    public CompletableFuture<JsonElement> updateStats(String bucket, UnaryOperator<JsonElement> change) {
        return storage().updateShared(STATS_NS, bucket, change);
    }

    public CompletableFuture<List<String>> listStats() {
        return storage().listKeys(STATS_NS);
    }

    public CompletableFuture<Boolean> deleteStats(String bucket) {
        return storage().delete(STATS_NS, bucket);
    }

    /** A player's luck tallies per pool revision. */
    public CompletableFuture<JsonElement> updatePlayerStats(UUID player, UnaryOperator<JsonElement> change) {
        return storage().updateShared(STATS_PLAYER_NS, player.toString(), change);
    }

    // ----- Pending inbox ------------------------------------------------------

    public CompletableFuture<PendingInbox> loadPending(UUID player) {
        return load(PENDING_NS, player.toString(), PendingInbox.class)
                .thenApply(inbox -> inbox == null ? new PendingInbox() : inbox);
    }

    /** Fenced like {@link #updatePlayer}: a stale lease can't add a second copy of a reward. */
    public CompletableFuture<PendingInbox> updatePending(UUID player, UnaryOperator<PendingInbox> change) {
        long lease = fenceOf(player);
        return update(PENDING_NS, player.toString(), PendingInbox.class, inbox -> {
            PendingInbox current = inbox == null ? new PendingInbox() : inbox;
            NetworkRules.admit(current.fence, lease);
            PendingInbox next = change.apply(current);
            if (next != null) {
                next.fence = NetworkRules.after(current.fence, lease);
            }
            return next;
        });
    }

    // ----- Crates -------------------------------------------------------------

    public CompletableFuture<List<String>> listCrates() {
        return storage().listKeys(CRATE_NS);
    }

    public CompletableFuture<CrateInstance> loadCrate(String crateId) {
        return load(CRATE_NS, crateId, CrateInstance.class);
    }

    public CompletableFuture<Void> saveCrate(CrateInstance crate) {
        return storage().save(CRATE_NS, crate.crateId, Json.toTree(crate));
    }

    public CompletableFuture<Boolean> deleteCrate(String crateId) {
        return storage().delete(CRATE_NS, crateId);
    }

    // ----- Audit --------------------------------------------------------------

    /** Appends to an hourly bucket so retention can drop whole buckets. */
    public CompletableFuture<Void> appendAudit(String bucket, JsonObject entry) {
        return storage().updateShared(AUDIT_NS, bucket, element -> {
            JsonArray entries = element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
            entries.add(entry);
            return entries;
        }).thenApply(stored -> null);
    }

    public CompletableFuture<List<String>> listAuditBuckets() {
        return storage().listKeys(AUDIT_NS);
    }

    public CompletableFuture<JsonElement> loadAuditBucket(String bucket) {
        return storage().load(AUDIT_NS, bucket);
    }

    public CompletableFuture<Boolean> deleteAuditBucket(String bucket) {
        return storage().delete(AUDIT_NS, bucket);
    }

    // ----- Helpers ------------------------------------------------------------

    private <T> CompletableFuture<T> load(String namespace, String key, Class<T> type) {
        return storage().load(namespace, key).thenApply(element ->
                element == null || element.isJsonNull() ? null : Json.gson().fromJson(element, type));
    }

    private <T> CompletableFuture<T> update(String namespace, String key, Class<T> type, UnaryOperator<T> change) {
        return storage().updateShared(namespace, key, element -> {
            T current = element == null || element.isJsonNull() ? null : Json.gson().fromJson(element, type);
            T next = change.apply(current);
            return next == null ? null : Json.toTree(next);
        }).thenApply(stored -> stored == null || stored.isJsonNull() ? null : Json.gson().fromJson(stored, type));
    }

    // ----- Keys track (P2-F2, P2-F3, P2-K) --------------------------------------
    // Added at the end so the parallel tracks' edits to this file do not meet;
    // the model types are spelled out for the same reason.

    static final String KEYLOG_NS = "lootbox_keylog";

    /** The key's recent grants and revocations; an empty log when it has none. */
    public CompletableFuture<org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyLog> loadKeyLog(
            String keyId) {
        return load(KEYLOG_NS, keyIdKey(keyId), org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyLog.class)
                .thenApply(log -> log == null ? new org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyLog() : log);
    }

    /** Appends one entry, keeping the newest {@code max}. */
    public CompletableFuture<org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyLog> appendKeyLog(
            String keyId, org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyLog.Entry entry, int max) {
        return update(KEYLOG_NS, keyIdKey(keyId), org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyLog.class,
                log -> {
                    var next = log == null ? new org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyLog() : log;
                    next.add(entry, max);
                    return next;
                });
    }

    static final String BATCH_NS = "lootbox_batch";

    public CompletableFuture<org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch> loadBatch(String batch) {
        return load(BATCH_NS, batch, org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch.class);
    }

    public CompletableFuture<Void> saveBatch(org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch batch) {
        return storage().save(BATCH_NS, batch.batch, Json.toTree(batch));
    }

    /** Atomic per batch: the commit point of stackable-key openings and deposits. */
    public CompletableFuture<org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch> updateBatch(String batch,
            UnaryOperator<org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch> change) {
        return update(BATCH_NS, batch, org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch.class, change);
    }

    // ----- Crate effects (P2-I) -------------------------------------------------

    static final String CRATE_PREFS_NS = "lootbox_crate_prefs";
    private static final String EFFECTS_OFF_KEY = "effects_off";

    /** Players who turned crate particles and sounds off, as UUID strings. */
    public CompletableFuture<List<String>> loadCrateEffectsOff() {
        return storage().load(CRATE_PREFS_NS, EFFECTS_OFF_KEY).thenApply(element -> {
            List<String> players = new java.util.ArrayList<>();
            if (element != null && element.isJsonArray()) {
                element.getAsJsonArray().forEach(entry -> players.add(entry.getAsString()));
            }
            return players;
        });
    }

    /** Adds or removes {@code player} from the crate-effects opt-out list in one read-modify-write. */
    public CompletableFuture<Void> setCrateEffectsOff(UUID player, boolean off) {
        String id = player.toString();
        return storage().updateShared(CRATE_PREFS_NS, EFFECTS_OFF_KEY, element -> {
            JsonArray players = element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
            JsonArray next = new JsonArray();
            players.forEach(entry -> {
                if (!entry.getAsString().equals(id)) {
                    next.add(entry);
                }
            });
            if (off) {
                next.add(id);
            }
            return next;
        }).thenApply(stored -> null);
    }
}
