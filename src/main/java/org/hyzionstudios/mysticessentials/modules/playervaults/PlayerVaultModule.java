package org.hyzionstudios.mysticessentials.modules.playervaults;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.MysticEssentialsAPI;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.PlayerVaultService;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.VaultOpenMode;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.results.VaultLockResult;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.results.VaultSaveResult;
import org.hyzionstudios.mysticessentials.modules.playervaults.command.PlayerVaultCommand;
import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultMetadata;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.PlayerVaultLockService;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.PlayerVaultPermissionService;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.PlayerVaultServiceImpl;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.VaultAdminLogService;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.VaultBackupService;
import org.hyzionstudios.mysticessentials.modules.playervaults.storage.PlayerVaultRedisBridge;
import org.hyzionstudios.mysticessentials.modules.playervaults.storage.PlayerVaultStorage;
import org.hyzionstudios.mysticessentials.modules.playervaults.ui.PlayerVaultUiController;

import com.hypixel.hytale.server.core.command.system.CommandRegistration;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Player Vaults: permission-based personal storage with an optional Redis-backed
 * cross-server safe-save workflow. Ships <b>disabled</b> — it must be enabled in
 * both the main config's modules map and {@code modules/playervaults/config.json}.
 *
 * <p>Wires the storage, Redis, lock, permission, backup, log, orchestration, and
 * UI layers together, exposes the public {@link PlayerVaultService} (resolvable
 * through {@code getModule("playervaults")}), and owns the module lifecycle.</p>
 *
 * <p>When {@code crossServer.enabled} and {@code requireRedis} are both set but
 * Redis is not connected, the module refuses to enable rather than silently
 * running local-only — a safety stop so a misconfigured network cannot open
 * vaults without the distributed lock that prevents cross-server duplication.</p>
 */
public final class PlayerVaultModule extends AbstractMysticModule implements PlayerVaultService {

    private PlayerVaultConfig config = new PlayerVaultConfig();

    private PlayerVaultStorage storage;
    private PlayerVaultRedisBridge redisBridge;
    private PlayerVaultPermissionService permissionService;
    private PlayerVaultLockService lockService;
    private VaultBackupService backupService;
    private VaultAdminLogService logService;
    private PlayerVaultServiceImpl service;
    private PlayerVaultUiController uiController;

    private CommandRegistration commandRegistration;
    private boolean active;

    public PlayerVaultModule() {
        super("playervaults", "Player Vaults", "1.0.0");
    }

    @Override
    public void onLoad(MysticEssentialsAPI api) {
        super.onLoad(api);
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), PlayerVaultConfig.class, new PlayerVaultConfig());
        if (!config.enabled) {
            log("Player Vaults is disabled in modules/playervaults/config.json; not registering.");
            return;
        }
        if (!validateConfig()) {
            return;
        }

        storage = new PlayerVaultStorage(core);
        redisBridge = new PlayerVaultRedisBridge(core, config);
        permissionService = new PlayerVaultPermissionService(core, config);
        lockService = new PlayerVaultLockService(core, redisBridge, config);
        backupService = new VaultBackupService(core, storage, config);
        logService = new VaultAdminLogService(core, storage, config);
        service = new PlayerVaultServiceImpl(core, storage, redisBridge, lockService,
                permissionService, backupService, config);
        uiController = new PlayerVaultUiController(core, service, permissionService,
                backupService, logService, config);
        service.setUiController(uiController);

        commandRegistration = core.platform().registerCommand(
                new PlayerVaultCommand(core, this));

        // Cross-server: drop our cached copy when a peer writes a vault.
        redisBridge.subscribeUpdates(notice -> redisBridge.invalidate(notice.owner(), notice.vaultNumber()));

        // Release a player's held vault lock cleanly if they disconnect mid-session.
        registerEvent(
                com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent.class,
                (com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent event) ->
                        uiController.closeSession(event.getPlayerRef().getUuid(), true));

        active = true;
        log("Player Vaults enabled (crossServer=" + config.crossServer.enabled
                + ", redis=" + (redisBridge.isActive() ? "active" : "local-only")
                + ", storage=" + core.getStorageService().activeProvider() + ").");
    }

    /** @return {@code true} if the config is safe to run; logs and returns false otherwise. */
    private boolean validateConfig() {
        if (config.crossServer.lockRenewSeconds >= config.crossServer.lockTtlSeconds) {
            core.log(Level.WARNING, "[playervaults] lockRenewSeconds (" + config.crossServer.lockRenewSeconds
                    + ") must be < lockTtlSeconds (" + config.crossServer.lockTtlSeconds
                    + "); clamping renew to half the TTL.");
            config.crossServer.lockRenewSeconds = Math.max(1, config.crossServer.lockTtlSeconds / 2);
        }
        if (config.maxRows < 1) {
            config.maxRows = 1;
        }
        if (config.crossServer.enabled && config.crossServer.requireRedis
                && (core.redis() == null || !core.redis().isEnabled())) {
            core.log(Level.SEVERE, "[playervaults] crossServer.enabled and requireRedis are set, but Redis is "
                    + "not connected. Refusing to enable Player Vaults to prevent cross-server item duplication. "
                    + "Enable Redis in config.json or set crossServer.requireRedis=false.");
            return false;
        }
        return true;
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), PlayerVaultConfig.class, new PlayerVaultConfig());
        if (!active) {
            return;
        }
        permissionService.updateConfig(config);
        redisBridge.updateConfig(config);
        lockService.updateConfig(config);
        backupService.updateConfig(config);
        logService.updateConfig(config);
        service.updateConfig(config);
        uiController.updateConfig(config);
        log("Player Vaults configuration reloaded.");
    }

    @Override
    public void onDisable() {
        if (!active) {
            return;
        }
        // Save open sessions and release their locks before tearing down.
        uiController.closeAll();
        lockService.releaseAllLocal();
        if (commandRegistration != null) {
            try {
                commandRegistration.unregister();
            } catch (Throwable ignored) {
                // Best effort; engine may already have dropped it on shutdown.
            }
            commandRegistration = null;
        }
        active = false;
    }

    // ----- Accessors for the command layer --------------------------------------

    public PlayerVaultConfig config() {
        return config;
    }

    public PlayerVaultUiController ui() {
        return uiController;
    }

    public PlayerVaultPermissionService permissions() {
        return permissionService;
    }

    public PlayerVaultServiceImpl serviceImpl() {
        return service;
    }

    public PlayerVaultLockService lockService() {
        return lockService;
    }

    public VaultBackupService backupService() {
        return backupService;
    }

    public VaultAdminLogService logService() {
        return logService;
    }

    // ----- PlayerVaultService (delegates to the orchestration impl) --------------

    @Override
    public CompletableFuture<Optional<PlayerVault>> getVault(UUID ownerUuid, int vaultNumber) {
        return service.getVault(ownerUuid, vaultNumber);
    }

    @Override
    public CompletableFuture<VaultSaveResult> saveVault(PlayerVault vault, long expectedVersion) {
        return service.saveVault(vault, expectedVersion);
    }

    @Override
    public CompletableFuture<VaultLockResult> lockVault(UUID ownerUuid, int vaultNumber,
            UUID viewerUuid, VaultOpenMode mode) {
        return service.lockVault(ownerUuid, vaultNumber, viewerUuid, mode);
    }

    @Override
    public CompletableFuture<Void> unlockVault(UUID ownerUuid, int vaultNumber, String lockToken) {
        return service.unlockVault(ownerUuid, vaultNumber, lockToken);
    }

    @Override
    public int getAllowedVaultCount(PlayerRef player) {
        return service.getAllowedVaultCount(player);
    }

    @Override
    public int getAllowedRows(PlayerRef player) {
        return service.getAllowedRows(player);
    }

    @Override
    public CompletableFuture<List<VaultMetadata>> getVaultMetadata(UUID ownerUuid) {
        return service.getVaultMetadata(ownerUuid);
    }

    @Override
    public void openVaultList(PlayerRef player) {
        uiController.openVaultList(player);
    }

    @Override
    public void openOwnVault(PlayerRef player, int vaultNumber) {
        uiController.openOwnVault(player, vaultNumber);
    }
}
