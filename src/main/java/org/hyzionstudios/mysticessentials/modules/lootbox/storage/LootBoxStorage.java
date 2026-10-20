package org.hyzionstudios.mysticessentials.modules.lootbox.storage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Document IO for LootBox over the Core {@link StorageService}, so every provider
 * (JSON, MySQL, MariaDB) works. One namespace per document type; keys are kept
 * filesystem-safe because the JSON provider uses them as file names (key ids'
 * {@code :} becomes {@code .}).
 *
 * <p>{@link StorageService#update} runs each read-modify-write after every earlier
 * operation on the same key, which is what makes the single-document commits of
 * the opening saga atomic on one server.</p>
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

    private final Supplier<StorageService> storage;

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

    /** Atomic per player: the commit point of virtual-key openings. {@code change} gets a fresh state if none is stored. */
    public CompletableFuture<PlayerLootState> updatePlayer(UUID player, UnaryOperator<PlayerLootState> change) {
        return update(PLAYER_NS, player.toString(), PlayerLootState.class,
                state -> change.apply(state == null ? new PlayerLootState() : state));
    }

    // ----- Openings -----------------------------------------------------------

    public CompletableFuture<Opening> loadOpening(String openingId) {
        return load(OPEN_NS, openingId, Opening.class);
    }

    public CompletableFuture<Void> saveOpening(Opening opening) {
        return storage().save(OPEN_NS, opening.openingId, Json.toTree(opening));
    }

    public CompletableFuture<Opening> updateOpening(String openingId, UnaryOperator<Opening> change) {
        return update(OPEN_NS, openingId, Opening.class, change);
    }

    // ----- Pending inbox ------------------------------------------------------

    public CompletableFuture<PendingInbox> loadPending(UUID player) {
        return load(PENDING_NS, player.toString(), PendingInbox.class)
                .thenApply(inbox -> inbox == null ? new PendingInbox() : inbox);
    }

    public CompletableFuture<PendingInbox> updatePending(UUID player, UnaryOperator<PendingInbox> change) {
        return update(PENDING_NS, player.toString(), PendingInbox.class,
                inbox -> change.apply(inbox == null ? new PendingInbox() : inbox));
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
        return storage().update(AUDIT_NS, bucket, element -> {
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
        return storage().update(namespace, key, element -> {
            T current = element == null || element.isJsonNull() ? null : Json.gson().fromJson(element, type);
            T next = change.apply(current);
            return next == null ? null : Json.toTree(next);
        }).thenApply(stored -> stored == null || stored.isJsonNull() ? null : Json.gson().fromJson(stored, type));
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
        return storage().update(CRATE_PREFS_NS, EFFECTS_OFF_KEY, element -> {
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
