package org.hyzionstudios.mysticessentials.modules.playervaults.storage;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVaultProfile;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultAdminLogEntry;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultBackup;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultConflictSnapshot;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

/**
 * The permanent source of truth for vault data, layered over the Core
 * {@link StorageService} so it works with any provider (JSON, MySQL, MariaDB).
 * Each document type gets its own namespace; keys are filesystem/column safe
 * ({@code <uuid>} or {@code <uuid>_v<number>}, both well under the 128-char SQL
 * id limit and free of path separators).
 *
 * <p>This class only does document IO and version reads. The versioned-save
 * decision (compare-and-write, conflict handling) lives in the service layer,
 * which serializes writes per vault behind the distributed lock and the local
 * session registry.</p>
 */
public final class PlayerVaultStorage {

    static final String VAULT_NS = "playervaults";
    static final String PROFILE_NS = "playervaults_profiles";
    static final String BACKUP_NS = "playervaults_backups";
    static final String CONFLICT_NS = "playervaults_conflicts";
    static final String LOG_NS = "playervaults_logs";

    private static final Type BACKUP_LIST = new TypeToken<ArrayList<VaultBackup>>() {
    }.getType();
    private static final Type CONFLICT_LIST = new TypeToken<ArrayList<VaultConflictSnapshot>>() {
    }.getType();
    private static final Type LOG_LIST = new TypeToken<ArrayList<VaultAdminLogEntry>>() {
    }.getType();

    private final MysticCore core;

    public PlayerVaultStorage(MysticCore core) {
        this.core = core;
    }

    private StorageService storage() {
        return core.getStorageService();
    }

    static String vaultKey(UUID owner, int vaultNumber) {
        return owner + "_v" + vaultNumber;
    }

    // ----- Vaults ---------------------------------------------------------------

    public CompletableFuture<Optional<PlayerVault>> loadVault(UUID owner, int vaultNumber) {
        return storage().load(VAULT_NS, vaultKey(owner, vaultNumber)).thenApply(element -> {
            if (element == null) {
                return Optional.empty();
            }
            PlayerVault vault = Json.gson().fromJson(element, PlayerVault.class);
            return Optional.ofNullable(vault);
        });
    }

    /** @return the stored version of a vault, or {@code 0} if it has never been saved. */
    public CompletableFuture<Long> loadVersion(UUID owner, int vaultNumber) {
        return loadVault(owner, vaultNumber).thenApply(vault -> vault.map(v -> v.version).orElse(0L));
    }

    public CompletableFuture<Void> saveVault(PlayerVault vault) {
        UUID owner = UUID.fromString(vault.ownerUuid);
        return storage().save(VAULT_NS, vaultKey(owner, vault.vaultNumber), Json.toTree(vault));
    }

    public CompletableFuture<Boolean> deleteVault(UUID owner, int vaultNumber) {
        return storage().delete(VAULT_NS, vaultKey(owner, vaultNumber));
    }

    // ----- Profiles -------------------------------------------------------------

    public CompletableFuture<Optional<PlayerVaultProfile>> loadProfile(UUID owner) {
        return storage().load(PROFILE_NS, owner.toString()).thenApply(element ->
                element == null ? Optional.empty()
                        : Optional.ofNullable(Json.gson().fromJson(element, PlayerVaultProfile.class)));
    }

    public CompletableFuture<Void> saveProfile(PlayerVaultProfile profile) {
        return storage().save(PROFILE_NS, profile.playerUuid, Json.toTree(profile));
    }

    // ----- Backups --------------------------------------------------------------

    public CompletableFuture<List<VaultBackup>> loadBackups(UUID owner, int vaultNumber) {
        return storage().load(BACKUP_NS, vaultKey(owner, vaultNumber)).thenApply(element -> {
            List<VaultBackup> list = element == null ? null : Json.gson().fromJson(element, BACKUP_LIST);
            return list != null ? list : new ArrayList<>();
        });
    }

    public CompletableFuture<Void> saveBackups(UUID owner, int vaultNumber, List<VaultBackup> backups) {
        return storage().save(BACKUP_NS, vaultKey(owner, vaultNumber), Json.toTree(backups));
    }

    // ----- Conflict snapshots ---------------------------------------------------

    public CompletableFuture<List<VaultConflictSnapshot>> loadConflicts(UUID owner, int vaultNumber) {
        return storage().load(CONFLICT_NS, vaultKey(owner, vaultNumber)).thenApply(element -> {
            List<VaultConflictSnapshot> list = element == null ? null
                    : Json.gson().fromJson(element, CONFLICT_LIST);
            return list != null ? list : new ArrayList<>();
        });
    }

    public CompletableFuture<Void> saveConflicts(UUID owner, int vaultNumber, List<VaultConflictSnapshot> snapshots) {
        return storage().save(CONFLICT_NS, vaultKey(owner, vaultNumber), Json.toTree(snapshots));
    }

    // ----- Admin logs -----------------------------------------------------------

    public CompletableFuture<List<VaultAdminLogEntry>> loadLogs(UUID target) {
        return storage().load(LOG_NS, target.toString()).thenApply(element -> {
            List<VaultAdminLogEntry> list = element == null ? null : Json.gson().fromJson(element, LOG_LIST);
            return list != null ? list : new ArrayList<>();
        });
    }

    public CompletableFuture<Void> saveLogs(UUID target, List<VaultAdminLogEntry> entries) {
        return storage().save(LOG_NS, target.toString(), Json.toTree(entries));
    }

    /** Raw element passthrough (used by admin export). */
    public CompletableFuture<JsonElement> exportVault(UUID owner, int vaultNumber) {
        return storage().load(VAULT_NS, vaultKey(owner, vaultNumber));
    }
}
