package org.hyzionstudios.mysticessentials.modules.playervaults.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.VaultOpenMode;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.results.VaultLockResult;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.results.VaultSaveResult;
import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;
import org.hyzionstudios.mysticessentials.modules.playervaults.event.PlayerVaultAdminOpenEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.event.PlayerVaultCloseEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.event.PlayerVaultEditMetadataEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.event.PlayerVaultOpenEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.event.PlayerVaultRestoreEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultItemStack;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultLock;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultMetadata;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.PlayerVaultPermissionService;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.PlayerVaultServiceImpl;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.VaultAdminLogService;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.VaultBackupService;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.VaultItemCatalog;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.VaultItemCodec;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultAdminLogEntry;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Owns the lifecycle of open vault sessions: it resolves permission + lock +
 * data into a live {@link VaultSession}, opens the container page, keeps the
 * distributed lock renewed, auto-saves on an interval, and tears everything down
 * on close. Item movement (deposit/withdraw) is <b>server-authoritative</b> and
 * runs on the owning player's world thread, so the client can never fabricate or
 * duplicate items — the server reads the real inventory, moves stacks, and
 * writes the versioned vault.
 */
public final class PlayerVaultUiController {

    private final MysticCore core;
    private final PlayerVaultServiceImpl service;
    private final PlayerVaultPermissionService permissions;
    private final VaultBackupService backupService;
    private final VaultAdminLogService logService;
    private PlayerVaultConfig config;

    /** viewer UUID -> their currently open vault session (one at a time). */
    private final ConcurrentHashMap<UUID, VaultSession> sessions = new ConcurrentHashMap<>();

    public PlayerVaultUiController(MysticCore core, PlayerVaultServiceImpl service,
            PlayerVaultPermissionService permissions, VaultBackupService backupService,
            VaultAdminLogService logService, PlayerVaultConfig config) {
        this.core = core;
        this.service = service;
        this.permissions = permissions;
        this.backupService = backupService;
        this.logService = logService;
        this.config = config;
    }

    public void updateConfig(PlayerVaultConfig config) {
        this.config = config;
    }

    PlayerVaultConfig config() {
        return config;
    }

    PlayerVaultPermissionService permissions() {
        return permissions;
    }

    PlayerVaultServiceImpl service() {
        return service;
    }

    // ----- Vault list -----------------------------------------------------------

    /** Opens the caller's own vault list dashboard. */
    public void openVaultList(PlayerRef player) {
        openVaultListFor(player, player.getUuid(), player.getUsername(), false);
    }

    /**
     * Loads every visible vault's data first (the builtin list pattern needs data
     * in hand when {@code build} runs), then opens the dashboard. {@code adminView}
     * shows another player's vaults.
     */
    public void openVaultListFor(PlayerRef viewer, UUID ownerUuid, String ownerName, boolean adminView) {
        int allowedVaults = adminView ? config.maxVaults : permissions.allowedVaultCount(viewer);
        int allowedRows = adminView ? config.maxRows : permissions.allowedRows(viewer);
        int limit = Math.min(Math.max(allowedVaults, 1), config.maxVaults);

        List<CompletableFuture<java.util.Optional<PlayerVault>>> loads = new ArrayList<>();
        for (int number = 1; number <= limit; number++) {
            loads.add(service.getVault(ownerUuid, number));
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((v, error) -> {
            List<PlayerVault> vaults = new ArrayList<>(limit);
            for (CompletableFuture<java.util.Optional<PlayerVault>> load : loads) {
                vaults.add(load.join().orElse(null));
            }
            core.platform().openPage(viewer, new VaultListUi(core, this, viewer, ownerUuid, ownerName,
                    allowedVaults, allowedRows, adminView, vaults));
        });
    }

    /** In-progress editor field values, preserved across search/icon-pick refreshes. */
    public record EditorDraft(String name, String color, String icon, String description) {
    }

    /** Loads a vault's metadata, then opens the editor for it (fresh, no draft, empty search). */
    public void openEditor(PlayerRef viewer, UUID ownerUuid, String ownerName, int vaultNumber, boolean adminView) {
        openEditorState(viewer, ownerUuid, ownerName, vaultNumber, adminView, "", null);
    }

    /**
     * Opens the editor with a search query and optional in-progress {@code draft}.
     * The item catalogue is filtered here (off the page build) and the matching
     * item ids are handed to the page so {@code build} stays synchronous.
     */
    public void openEditorState(PlayerRef viewer, UUID ownerUuid, String ownerName, int vaultNumber,
            boolean adminView, String query, EditorDraft draft) {
        int rows = adminView ? config.maxRows : permissions.allowedRows(viewer);
        service.loadOrCreate(ownerUuid, vaultNumber, Math.max(1, rows)).whenComplete((vault, error) -> {
            VaultMetadata metadata = vault == null || vault.metadata == null
                    ? new VaultMetadata() : vault.metadata.copy();
            List<String> results = VaultItemCatalog.search(query, config.iconPickerResultLimit);
            core.platform().openPage(viewer, new VaultEditorUi(core, this, viewer, ownerUuid, ownerName,
                    vaultNumber, adminView, metadata, draft, query == null ? "" : query, results));
        });
    }

    // ----- Opening a vault ------------------------------------------------------

    /** Opens one of the caller's own numbered vaults, with a clean denial message. */
    public void openOwnVault(PlayerRef player, int vaultNumber) {
        if (vaultNumber < 1 || vaultNumber > config.maxVaults) {
            core.getMessageService().sendKey(player, "vault-invalid-number",
                    Map.of("max", Integer.toString(config.maxVaults)));
            return;
        }
        if (!permissions.canAccessVault(player, vaultNumber)) {
            core.getMessageService().sendKey(player, "vault-no-access",
                    Map.of("vault", Integer.toString(vaultNumber)));
            return;
        }
        int allowedRows = permissions.allowedRows(player);
        beginSession(player, player.getUuid(), player.getUsername(), vaultNumber,
                VaultOpenMode.PLAYER, allowedRows, true);
    }

    /**
     * Opens another player's vault for staff. Permission, mode, and offline
     * eligibility are decided by the caller; this fires the admin-open event,
     * writes an audit entry, and starts the session.
     */
    public void openAdminVault(PlayerRef viewer, UUID ownerUuid, String ownerName, int vaultNumber,
            VaultOpenMode mode, boolean onlineTarget) {
        if (vaultNumber < 1 || vaultNumber > config.maxVaults) {
            core.getMessageService().sendKey(viewer, "vault-invalid-number",
                    Map.of("max", Integer.toString(config.maxVaults)));
            return;
        }
        PlayerVaultAdminOpenEvent event = new PlayerVaultAdminOpenEvent(viewer.getUuid(), ownerUuid,
                vaultNumber, mode, onlineTarget);
        if (core.getEventBus() != null) {
            core.getEventBus().publish(event);
        }
        if (event.isCancelled()) {
            core.getMessageService().sendKey(viewer, "vault-no-access",
                    Map.of("vault", Integer.toString(vaultNumber)));
            return;
        }
        // Admins may see overflow beyond the owner's rank when they hold bypasslimit.
        int rows = permissions.canBypassLimit(viewer) ? config.maxRows : config.maxRows;
        if (config.admin.logAdminOpens) {
            logService.record(VaultAdminLogEntry.create(viewer.getUuid(), viewer.getUsername(),
                    ownerUuid, vaultNumber, "ADMIN_OPEN", logService.serverId())
                    .with("mode", mode.name())
                    .with("onlineTarget", Boolean.toString(onlineTarget)));
        }
        if (mode.isMutable() && config.admin.notifyOnlinePlayerWhenAdminOpensVault) {
            core.platform().findPlayer(ownerUuid).ifPresent(owner ->
                    core.getMessageService().sendKey(owner, "vault-admin-viewing",
                            Map.of("player", viewer.getUsername(), "vault", Integer.toString(vaultNumber))));
        }
        beginSession(viewer, ownerUuid, ownerName, vaultNumber, mode, rows, true);
        core.getMessageService().sendKey(viewer, "vault-admin-opened", Map.of(
                "player", ownerName == null ? ownerUuid.toString() : ownerName,
                "vault", Integer.toString(vaultNumber),
                "mode", mode.name()));
    }

    /**
     * Shared open path: fire the open event, take the lock, load the working
     * copy, register the session, and open the container page.
     */
    private void beginSession(PlayerRef viewer, UUID ownerUuid, String ownerName, int vaultNumber,
            VaultOpenMode mode, int allowedRows, boolean warnOverflow) {
        // One open session per viewer: close any previous first (saving it).
        VaultSession previous = sessions.get(viewer.getUuid());
        if (previous != null) {
            closeSession(viewer.getUuid(), true);
        }

        PlayerVaultOpenEvent openEvent = new PlayerVaultOpenEvent(viewer.getUuid(), ownerUuid, vaultNumber, mode);
        if (core.getEventBus() != null) {
            core.getEventBus().publish(openEvent);
        }
        if (openEvent.isCancelled()) {
            core.getMessageService().sendKey(viewer, "vault-no-access",
                    Map.of("vault", Integer.toString(vaultNumber)));
            return;
        }

        service.lockVault(ownerUuid, vaultNumber, viewer.getUuid(), mode).thenAccept(lock -> {
            if (!lock.acquired()) {
                messageLockFailure(viewer, vaultNumber, lock);
                return;
            }
            service.loadOrCreate(ownerUuid, vaultNumber, Math.max(allowedRows, 1)).whenComplete((vault, error) -> {
                if (error != null || vault == null) {
                    // Loading failed: never leave a dangling lock.
                    service.lockService().release(ownerUuid, vaultNumber, lock.token());
                    core.getMessageService().sendKey(viewer, "vault-storage-unavailable");
                    return;
                }
                vault.lastOpenedAt = System.currentTimeMillis();
                VaultSession session = new VaultSession(viewer.getUuid(), ownerUuid, ownerName, vaultNumber,
                        mode, allowedRows, lock.token(), vault, vault.version);
                sessions.put(viewer.getUuid(), session);
                scheduleMaintenance(session);
                if (warnOverflow && mode == VaultOpenMode.PLAYER
                        && vault.hasOverflow(allowedRows, config.slotsPerRow)) {
                    core.getMessageService().sendKey(viewer, "vault-overflow-notice",
                            Map.of("rows", Integer.toString(allowedRows)));
                }
                if (mode.isReadOnly()) {
                    core.getMessageService().sendKey(viewer, "vault-readonly");
                }
                openContainerWindow(viewer, session);
            });
        });
    }

    /**
     * Opens the vault as a <b>native draggable container window</b> — a real
     * chest-style grid with the viewer's own inventory shown beneath it — instead
     * of a custom page. The window is backed by a {@link SimpleItemContainer}
     * seeded from the working copy; the engine ({@code InventoryPacketHandler})
     * handles all drag/shift-click moves, and we mirror each change back into the
     * versioned working copy for persistence. Runs on the viewer's world thread.
     */
    void openContainerWindow(PlayerRef viewer, VaultSession session) {
        core.platform().runOnEntityThread(viewer, (store, entity, world) -> {
            Player playerEntity = store.getComponent(entity, Player.getComponentType());
            if (playerEntity == null) {
                core.log(Level.WARNING, "[playervaults] openContainerWindow: no Player component for "
                        + viewer.getUsername());
                closeSession(session.viewerUuid, false);
                return;
            }
            PlayerVaultConfig cfg = this.config;
            PlayerVault vault = session.working;
            int slotsPerRow = Math.max(1, cfg.slotsPerRow);
            // Admins with bypasslimit (and admins opening an over-ranked vault) see overflow rows.
            boolean bypass = permissions.canBypassLimit(viewer)
                    || (vault.rows > session.allowedRows && session.mode.isAdmin());
            int viewRows = bypass ? Math.max(vault.rows, session.allowedRows) : session.allowedRows;
            int capacity = Math.max(1, viewRows) * slotsPerRow;
            boolean readOnly = session.mode.isReadOnly() || session.readOnlyDowngrade;

            SimpleItemContainer container = new SimpleItemContainer((short) capacity);
            // Seed the grid from the working copy (occupied, in-range slots only).
            for (VaultItemStack item : vault.items) {
                if (item.slot >= 0 && item.slot < capacity) {
                    try {
                        container.setItemStackForSlot((short) item.slot, VaultItemCodec.toLive(item));
                    } catch (Throwable t) {
                        core.log(Level.WARNING, "[playervaults] failed to seed vault slot "
                                + item.slot + ": " + t);
                    }
                }
            }
            // Gating is applied AFTER seeding so the initial fill is never rejected.
            if (readOnly) {
                container.setGlobalFilter(FilterType.DENY_ALL);
            } else if (cfg.preventStorageOfBlacklistedItems && cfg.blockedItemIds != null
                    && !cfg.blockedItemIds.isEmpty()) {
                SlotFilter blacklist = (action, cont, slot, stack) -> stack == null || stack.isEmpty()
                        || !VaultItemCodec.isBlockedForStorage(stack.getItemId(), cfg);
                for (short s = 0; s < capacity; s++) {
                    container.setSlotFilter(FilterActionType.ADD, s, blacklist);
                }
            }

            // Drive the client's native side info panel: it renders the name/icon/
            // description of whatever item id we put in windowData.blockItemId. Use the
            // vault's own icon (default icon otherwise) so each vault shows its icon.
            ContainerWindow window = new VaultContainerWindow(container, panelItemId(vault));
            int cap = capacity;
            if (!readOnly) {
                container.registerChangeEvent(evt -> onContainerChanged(session, container, cap));
            }
            window.registerCloseEvent(evt -> onWindowClosed(session, container, cap, !readOnly));

            try {
                // Native draggable chest: grid window + the player's own inventory panel,
                // opened as Page.Bench (mirrors /invsee and real chests). A custom side
                // panel CANNOT coexist with the grid (openCustomPageWithWindows hides it),
                // so vault name / nav / edit live on the vault-list dashboard instead.
                playerEntity.getPageManager().setPageWithWindows(entity, store,
                        com.hypixel.hytale.protocol.packets.interface_.Page.Bench, true, window);
            } catch (Throwable t) {
                core.log(Level.SEVERE, "[playervaults] openWindow failed for "
                        + viewer.getUsername() + ": " + t);
                closeSession(session.viewerUuid, false);
            }
        });
    }

    /** A drag/move happened in the live container: mirror it into the working copy and persist. */
    private void onContainerChanged(VaultSession session, ItemContainer container, int capacity) {
        if (session.mode.isReadOnly() || session.readOnlyDowngrade) {
            return;
        }
        syncContainerToSession(session, container, capacity);
        session.dirty = true;
        if (config.saving.writeThrough) {
            persist(session, false, "WRITE_THROUGH", null);
        }
    }

    /** The window closed (Esc/close): capture the final state, then save + release the lock. */
    private void onWindowClosed(VaultSession session, ItemContainer container, int capacity, boolean mutable) {
        if (mutable && !session.readOnlyDowngrade) {
            syncContainerToSession(session, container, capacity);
        }
        closeSession(session.viewerUuid, true);
    }

    /**
     * Rebuilds {@code session.working.items} from the live container's accessible
     * slots {@code [0, capacity)}, preserving any overflow items ({@code slot >=
     * capacity}) the grid never exposed so a rank downgrade can never delete them.
     */
    private void syncContainerToSession(VaultSession session, ItemContainer container, int capacity) {
        List<VaultItemStack> rebuilt = new ArrayList<>();
        for (VaultItemStack item : session.working.items) {
            if (item.slot >= capacity) {
                rebuilt.add(item); // untouched overflow beyond the visible grid
            }
        }
        for (short s = 0; s < capacity; s++) {
            ItemStack stack;
            try {
                stack = container.getItemStack(s);
            } catch (Throwable t) {
                continue;
            }
            VaultItemStack stored = VaultItemCodec.toStored(s, stack);
            if (stored != null) {
                rebuilt.add(stored);
            }
        }
        session.working.items = rebuilt;
    }

    private void messageLockFailure(PlayerRef viewer, int vaultNumber, VaultLockResult lock) {
        switch (lock.status()) {
            case LOCKED_ELSEWHERE -> {
                VaultLock holder = lock.holder();
                core.getMessageService().sendKey(viewer, "vault-locked-elsewhere", Map.of(
                        "server", holder == null || holder.ownerServerId == null ? "another server"
                                : holder.ownerServerId));
            }
            case LOCKED_LOCALLY -> core.getMessageService().sendKey(viewer, "vault-locked-local");
            case REDIS_UNAVAILABLE -> core.getMessageService().sendKey(viewer, "vault-storage-unavailable");
            default -> core.getMessageService().sendKey(viewer, "vault-storage-unavailable");
        }
    }

    // ----- Session maintenance --------------------------------------------------

    private void scheduleMaintenance(VaultSession session) {
        if (session.mode.isReadOnly()) {
            return; // read-only sessions neither renew a lock nor auto-save
        }
        long renewSeconds = Math.max(1, config.crossServer.lockRenewSeconds);
        session.renewalTask = core.scheduler().runRepeating(() -> renew(session),
                renewSeconds, renewSeconds, TimeUnit.SECONDS);
        long saveInterval = config.saving.saveIntervalSeconds;
        if (saveInterval > 0) {
            session.saveTask = core.scheduler().runRepeating(() -> intervalSave(session),
                    saveInterval, saveInterval, TimeUnit.SECONDS);
        }
    }

    private void renew(VaultSession session) {
        boolean held = service.lockService().renew(session.ownerUuid, session.vaultNumber, session.token);
        if (!held && !session.readOnlyDowngrade) {
            // Lost the lock (TTL lapse / force unlock elsewhere): fail safe to read-only.
            session.readOnlyDowngrade = true;
            cancelTasks(session);
            core.platform().findPlayer(session.viewerUuid).ifPresent(viewer ->
                    core.getMessageService().sendKey(viewer, "vault-readonly-downgrade"));
        }
    }

    private void intervalSave(VaultSession session) {
        if (session.dirty && !session.readOnlyDowngrade) {
            persist(session, false, "INTERVAL_SAVE", null);
        }
    }

    // ----- Icon-item lookup helper ----------------------------------------------

    /**
     * Item id for the native side-panel of an open vault chest: the vault's custom
     * icon if set, else the configured default icon. Blank ({@code ""}) leaves the
     * panel off. Mirrors {@code VaultListUi.iconItemId} so card and chest agree.
     */
    private String panelItemId(PlayerVault vault) {
        if (vault != null && vault.metadata != null && vault.metadata.icon != null
                && vault.metadata.icon.itemId != null && !vault.metadata.icon.itemId.isBlank()) {
            return vault.metadata.icon.itemId;
        }
        return config.defaultIconItemId == null ? "" : config.defaultIconItemId;
    }

    private static List<ItemContainer> depositSources(Inventory inventory) {
        List<ItemContainer> sources = new ArrayList<>();
        if (inventory.getStorage() != null) {
            sources.add(inventory.getStorage());
        }
        if (inventory.getBackpack() != null) {
            sources.add(inventory.getBackpack());
        }
        return sources;
    }

    // ----- Saving & closing -----------------------------------------------------

    private void persist(VaultSession session, boolean backupFirst, String reason, Runnable onDone) {
        // Serialize saves per session: only this server writes (it holds the lock),
        // so overlapping an interval save with a write-through save would otherwise
        // read the same expectedVersion twice and the second would false-conflict.
        if (!session.saving.compareAndSet(false, true)) {
            if (onDone != null) {
                onDone.run(); // still refresh the UI; the data persists on the next save
            }
            return;
        }
        service.versionedSave(session.working, session.expectedVersion, session.viewerUuid, backupFirst, reason)
                .whenComplete((result, error) -> {
                    session.saving.set(false);
                    if (error == null && result != null) {
                        handleSaveResult(session, result);
                    }
                    if (onDone != null) {
                        onDone.run();
                    }
                });
    }

    private void handleSaveResult(VaultSession session, VaultSaveResult result) {
        switch (result.status()) {
            case SAVED -> {
                session.dirty = false;
                // Adopt the new version so the next save compares correctly.
                session.expectedVersion = result.vault().version;
                session.working = result.vault();
            }
            case CONFLICT -> {
                // Another writer won: adopt stored truth, drop our stale edits from the session.
                session.dirty = false;
                session.working = result.vault();
                session.expectedVersion = result.vault().version;
                session.readOnlyDowngrade = true;
                cancelTasks(session);
                core.platform().findPlayer(session.viewerUuid).ifPresent(viewer ->
                        core.getMessageService().sendKey(viewer, "vault-conflict"));
            }
            case STORAGE_ERROR, LOCK_LOST -> core.platform().findPlayer(session.viewerUuid).ifPresent(viewer ->
                    core.getMessageService().sendKey(viewer, "vault-save-failed",
                            Map.of("vault", Integer.toString(session.vaultNumber))));
            default -> {
            }
        }
    }

    /** Closes and tears down the viewer's session, optionally doing a final save. */
    public void closeSession(UUID viewerUuid, boolean save) {
        VaultSession session = sessions.remove(viewerUuid);
        if (session == null) {
            return;
        }
        cancelTasks(session);
        boolean willSave = save && session.dirty && session.mode.isMutable() && !session.readOnlyDowngrade;
        Runnable finish = () -> {
            service.lockService().release(session.ownerUuid, session.vaultNumber, session.token);
            if (core.getEventBus() != null) {
                core.getEventBus().publish(new PlayerVaultCloseEvent(viewerUuid, session.ownerUuid,
                        session.vaultNumber, willSave));
            }
            if (session.mode.isAdmin() && config.admin.logAdminEdits && willSave) {
                logService.record(VaultAdminLogEntry.create(viewerUuid, session.ownerName,
                        session.ownerUuid, session.vaultNumber, "ADMIN_EDIT", logService.serverId())
                        .with("items", Integer.toString(session.working.totalItemCount())));
            }
        };
        // Skip the close-save if a write-through/interval save is already in flight —
        // that save persists the same working state; double-saving would false-conflict.
        if (willSave && config.saving.saveOnClose && session.saving.compareAndSet(false, true)) {
            service.versionedSave(session.working, session.expectedVersion, viewerUuid,
                    config.saving.saveBackups, "SAVE_ON_CLOSE").whenComplete((result, error) -> {
                        session.saving.set(false);
                        if (result != null) {
                            handleSaveResult(session, result);
                        }
                        finish.run();
                    });
        } else {
            finish.run();
        }
    }

    private void cancelTasks(VaultSession session) {
        if (session.renewalTask != null) {
            session.renewalTask.cancel(false);
            session.renewalTask = null;
        }
        if (session.saveTask != null) {
            session.saveTask.cancel(false);
            session.saveTask = null;
        }
    }

    /** Closes every open session (module disable / shutdown). */
    public void closeAll() {
        for (UUID viewer : new ArrayList<>(sessions.keySet())) {
            closeSession(viewer, true);
        }
    }

    VaultSession session(UUID viewerUuid) {
        return sessions.get(viewerUuid);
    }

    // ----- Admin restore --------------------------------------------------------

    /**
     * Restores a vault from a backup: snapshots the current state first, then
     * writes the backup's contents through the versioned-save path (so it can
     * never roll back newer data without a conflict record). Cancellable via
     * {@link PlayerVaultRestoreEvent} before anything changes.
     */
    public void restoreBackup(PlayerRef actor, UUID ownerUuid, String ownerName, int vaultNumber, String backupId) {
        backupService.find(ownerUuid, vaultNumber, backupId).whenComplete((backupOpt, error) -> {
            if (error != null) {
                core.getMessageService().sendKey(actor, "vault-storage-unavailable");
                return;
            }
            if (backupOpt.isEmpty()) {
                core.getMessageService().sendKey(actor, "vault-restore-unknown-backup",
                        Map.of("backup", backupId));
                return;
            }
            PlayerVaultRestoreEvent pre = new PlayerVaultRestoreEvent(actor.getUuid(), ownerUuid,
                    vaultNumber, backupOpt.get().backupId, PlayerVaultRestoreEvent.Phase.PRE);
            if (core.getEventBus() != null) {
                core.getEventBus().publish(pre);
            }
            if (pre.isCancelled()) {
                return;
            }
            // Read the current version so the restore compares against live truth.
            service.getVault(ownerUuid, vaultNumber).whenComplete((currentOpt, loadError) -> {
                long expected = currentOpt.map(vault -> vault.version).orElse(0L);
                PlayerVault restored = backupOpt.get().snapshot.copy();
                restored.ownerUuid = ownerUuid.toString();
                restored.vaultNumber = vaultNumber;
                restored.version = expected; // versionedSave will bump to expected + 1
                service.versionedSave(restored, expected, actor.getUuid(), true, "PRE_RESTORE")
                        .whenComplete((result, saveError) -> {
                            if (result == null || result.status() != VaultSaveResult.Status.SAVED) {
                                core.getMessageService().sendKey(actor, "vault-conflict");
                                return;
                            }
                            if (core.getEventBus() != null) {
                                core.getEventBus().publish(new PlayerVaultRestoreEvent(actor.getUuid(),
                                        ownerUuid, vaultNumber, backupOpt.get().backupId,
                                        PlayerVaultRestoreEvent.Phase.POST));
                            }
                            logService.record(VaultAdminLogEntry.create(actor.getUuid(), actor.getUsername(),
                                    ownerUuid, vaultNumber, "ADMIN_RESTORE", logService.serverId())
                                    .with("backup", backupOpt.get().shortId()));
                            core.getMessageService().sendKey(actor, "vault-restored", Map.of(
                                    "player", ownerName == null ? ownerUuid.toString() : ownerName,
                                    "vault", Integer.toString(vaultNumber),
                                    "backup", backupOpt.get().shortId()));
                        });
            });
        });
    }

    // ----- Metadata editing -----------------------------------------------------

    /** A single editor submission; {@code null} fields mean "leave unchanged". */
    record MetadataEdit(String name, String color, String iconItemId, String description) {
    }

    /**
     * Applies a metadata edit to a vault. Each field is gated by its own editor
     * permission and config toggle, sanitized, and passed through a cancellable
     * {@link PlayerVaultEditMetadataEvent}. The result is written with a fresh
     * versioned save so it never clobbers newer stored data.
     *
     * <p>{@code onDone} runs exactly once when the whole flow settles (save
     * complete or terminal failure), so callers can reopen the editor <i>after</i>
     * the save rather than racing it — the fix for edits that silently didn't
     * persist because the reopen loaded stale data mid-save.</p>
     */
    void applyMetadata(PlayerRef viewer, UUID ownerUuid, String ownerName, int vaultNumber,
            boolean admin, MetadataEdit edit, Runnable onDone) {
        if (admin && !permissions.canAdminEdit(viewer)) {
            core.getMessageService().sendKey(viewer, "no-permission");
            runQuietly(onDone);
            return;
        }
        int rows = admin ? config.maxRows : permissions.allowedRows(viewer);
        service.loadOrCreate(ownerUuid, vaultNumber, Math.max(1, rows)).whenComplete((vault, error) -> {
            if (error != null || vault == null) {
                core.getMessageService().sendKey(viewer, "vault-storage-unavailable");
                runQuietly(onDone);
                return;
            }
            VaultMetadata metadata = vault.metadata == null ? new VaultMetadata() : vault.metadata;
            vault.metadata = metadata;
            boolean changed = false;

            if (edit.name() != null && permissions.canEditName(viewer)) {
                String sanitized = sanitizeName(edit.name());
                if (sanitized == null) {
                    core.getMessageService().sendKey(viewer, "vault-name-invalid");
                    runQuietly(onDone);
                    return;
                }
                String applied = fireEdit(viewer, ownerUuid, vaultNumber,
                        PlayerVaultEditMetadataEvent.Field.NAME, metadata.name, sanitized);
                if (applied != null) {
                    metadata.name = applied.isBlank() ? null : applied;
                    changed = true;
                }
            }
            if (edit.color() != null && permissions.canEditColor(viewer)) {
                String color = normalizeColor(viewer, edit.color());
                String applied = fireEdit(viewer, ownerUuid, vaultNumber,
                        PlayerVaultEditMetadataEvent.Field.COLOR, metadata.color, color);
                if (applied != null) {
                    metadata.color = applied.isBlank() ? null : applied;
                    changed = true;
                }
            }
            if (edit.iconItemId() != null && permissions.canEditIcon(viewer)) {
                String iconId = edit.iconItemId().trim();
                if (!iconId.isEmpty() && VaultItemCodec.isBlockedForIcon(iconId, config)) {
                    core.getMessageService().sendKey(viewer, "vault-icon-invalid");
                    runQuietly(onDone);
                    return;
                }
                String applied = fireEdit(viewer, ownerUuid, vaultNumber,
                        PlayerVaultEditMetadataEvent.Field.ICON,
                        metadata.icon == null ? null : metadata.icon.itemId, iconId);
                if (applied != null) {
                    metadata.icon = applied.isBlank() ? null
                            : new VaultMetadata.Icon(applied, applied, null);
                    changed = true;
                    if (!applied.isBlank() && config.consumeIconItem) {
                        consumeIconItem(viewer, applied);
                    }
                }
            }
            if (edit.description() != null && permissions.canEditDescription(viewer)) {
                String desc = sanitizeDescription(edit.description());
                String applied = fireEdit(viewer, ownerUuid, vaultNumber,
                        PlayerVaultEditMetadataEvent.Field.DESCRIPTION, metadata.description, desc);
                if (applied != null) {
                    metadata.description = applied.isBlank() ? null : applied;
                    changed = true;
                }
            }

            if (!changed) {
                core.getMessageService().sendKey(viewer, "no-permission");
                runQuietly(onDone);
                return;
            }
            saveMetadata(viewer, vault, vaultNumber, admin, onDone);
        });
    }

    /** Resets a vault's metadata to defaults (contents untouched). {@code onDone} runs when settled. */
    void resetMetadata(PlayerRef viewer, UUID ownerUuid, String ownerName, int vaultNumber, boolean admin,
            Runnable onDone) {
        if (!permissions.canResetMetadata(viewer)) {
            core.getMessageService().sendKey(viewer, "no-permission");
            runQuietly(onDone);
            return;
        }
        int rows = admin ? config.maxRows : permissions.allowedRows(viewer);
        service.loadOrCreate(ownerUuid, vaultNumber, Math.max(1, rows)).whenComplete((vault, error) -> {
            if (error != null || vault == null) {
                core.getMessageService().sendKey(viewer, "vault-storage-unavailable");
                runQuietly(onDone);
                return;
            }
            fireEdit(viewer, ownerUuid, vaultNumber, PlayerVaultEditMetadataEvent.Field.RESET, "custom", "default");
            vault.metadata = new VaultMetadata();
            saveMetadata(viewer, vault, vaultNumber, admin, onDone);
        });
    }

    private void saveMetadata(PlayerRef viewer, PlayerVault vault, int vaultNumber, boolean admin, Runnable onDone) {
        service.versionedSave(vault, vault.version, viewer.getUuid(), false, "METADATA_EDIT")
                .whenComplete((result, error) -> {
                    try {
                        if (result == null || error != null) {
                            core.getMessageService().sendKey(viewer, "vault-storage-unavailable");
                            return;
                        }
                        switch (result.status()) {
                            case SAVED -> {
                                core.getMessageService().sendKey(viewer, "vault-metadata-updated",
                                        Map.of("vault", Integer.toString(vaultNumber)));
                                if (admin && config.admin.logAdminEdits) {
                                    logService.record(VaultAdminLogEntry.create(viewer.getUuid(),
                                            viewer.getUsername(), UUID.fromString(vault.ownerUuid), vaultNumber,
                                            "ADMIN_EDIT", logService.serverId()).with("field", "metadata"));
                                }
                            }
                            case CONFLICT -> core.getMessageService().sendKey(viewer, "vault-conflict");
                            default -> core.getMessageService().sendKey(viewer, "vault-storage-unavailable");
                        }
                    } finally {
                        runQuietly(onDone);
                    }
                });
    }

    /** Runs {@code r} if non-null, swallowing any error so a callback can't break the save flow. */
    private static void runQuietly(Runnable r) {
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
                // A failed UI reopen must not surface as a save failure.
            }
        }
    }

    /** Fires the cancellable edit event; returns the (possibly rewritten) value, or {@code null} if vetoed. */
    private String fireEdit(PlayerRef viewer, UUID ownerUuid, int vaultNumber,
            PlayerVaultEditMetadataEvent.Field field, String oldValue, String newValue) {
        PlayerVaultEditMetadataEvent event = new PlayerVaultEditMetadataEvent(viewer.getUuid(), ownerUuid,
                vaultNumber, field, oldValue, newValue);
        if (core.getEventBus() != null) {
            core.getEventBus().publish(event);
        }
        return event.isCancelled() ? null : event.getNewValue();
    }

    private String sanitizeName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.length() > config.maxNameLength) {
            trimmed = trimmed.substring(0, config.maxNameLength);
        }
        return trimmed;
    }

    private String sanitizeDescription(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.length() > config.maxDescriptionLength) {
            trimmed = trimmed.substring(0, config.maxDescriptionLength);
        }
        return trimmed;
    }

    private String normalizeColor(PlayerRef viewer, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.matches("#[0-9a-fA-F]{6}")) {
            // A raw hex value requires the hex permission; otherwise ignore it.
            return permissions.canEditHexColor(viewer) ? value : "";
        }
        return value; // preset name; the card renderer falls back if unknown
    }

    /** Best-effort consume of one icon item from the viewer's inventory (world thread). */
    private void consumeIconItem(PlayerRef viewer, String itemId) {
        core.platform().runOnEntityThread(viewer, (store, entity, world) -> {
            try {
                Player playerEntity = store.getComponent(entity, Player.getComponentType());
                Inventory inventory = playerEntity == null ? null : playerEntity.getInventory();
                if (inventory == null) {
                    return;
                }
                for (ItemContainer container : depositSources(inventory)) {
                    for (short i = 0; i < container.getCapacity(); i++) {
                        ItemStack stack = container.getItemStack(i);
                        if (stack == null || stack.isEmpty() || !itemId.equals(stack.getItemId())) {
                            continue;
                        }
                        int remaining = stack.getQuantity() - 1;
                        if (remaining <= 0) {
                            container.setItemStackForSlot(i, ItemStack.EMPTY);
                        } else {
                            container.setItemStackForSlot(i, new ItemStack(itemId, remaining));
                        }
                        core.getMessageService().sendKey(viewer, "vault-icon-consumed",
                                Map.of("item", itemId));
                        return;
                    }
                }
            } catch (Throwable t) {
                core.log(Level.WARNING, "[playervaults] icon consume failed: " + t);
            }
        });
    }

    VaultBackupService backupService() {
        return backupService;
    }

    VaultAdminLogService logService() {
        return logService;
    }

    // ----- Session state --------------------------------------------------------

    /** Mutable per-viewer state for one open vault. Confined to controller threads. */
    static final class VaultSession {
        final UUID viewerUuid;
        final UUID ownerUuid;
        final String ownerName;
        final int vaultNumber;
        final VaultOpenMode mode;
        final int allowedRows;
        final String token;

        volatile PlayerVault working;
        volatile long expectedVersion;
        volatile boolean dirty;
        volatile boolean readOnlyDowngrade;
        /** Guards against overlapping saves for this session (interval vs write-through vs close). */
        final java.util.concurrent.atomic.AtomicBoolean saving = new java.util.concurrent.atomic.AtomicBoolean();
        ScheduledFuture<?> renewalTask;
        ScheduledFuture<?> saveTask;

        VaultSession(UUID viewerUuid, UUID ownerUuid, String ownerName, int vaultNumber, VaultOpenMode mode,
                int allowedRows, String token, PlayerVault working, long expectedVersion) {
            this.viewerUuid = viewerUuid;
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.vaultNumber = vaultNumber;
            this.mode = mode;
            this.allowedRows = allowedRows;
            this.token = token;
            this.working = working;
            this.expectedVersion = expectedVersion;
        }
    }
}
