package org.hyzionstudios.mysticessentials.modules.playervaults.service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.VaultOpenMode;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.results.VaultLockResult;
import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultLock;
import org.hyzionstudios.mysticessentials.modules.playervaults.storage.PlayerVaultRedisBridge;

/**
 * Guards single-writer access to each vault, at two levels:
 *
 * <ol>
 *   <li><b>Local session registry</b> — an in-memory map preventing two UI
 *       sessions on <i>this</i> server from editing the same vault at once
 *       (also stops duplicate-open dupe tricks).</li>
 *   <li><b>Distributed Redis lock</b> — a {@code SET NX EX} lock keyed by
 *       {@code mysticessentials:vaults:lock:<owner>:<vault>} carrying a
 *       {@link VaultLock} payload with a strong random token. Only the holder of
 *       the exact token may renew or release it (compare-and-set), so a crashed
 *       or slow server can never stomp a lock that expired and was re-acquired
 *       elsewhere. The TTL is the crash-safety net: an abandoned lock frees
 *       itself after {@code lockTtlSeconds}.</li>
 * </ol>
 *
 * <p>Read-only admin views do not take an edit lock (they never write), and may
 * be permitted even while the vault is locked elsewhere when config allows.</p>
 */
public final class PlayerVaultLockService {

    private final MysticCore core;
    private final PlayerVaultRedisBridge redisBridge;
    private PlayerVaultConfig config;

    /** owner:vault -> the session currently holding it on this server. */
    private final ConcurrentHashMap<String, LocalSession> localSessions = new ConcurrentHashMap<>();

    public PlayerVaultLockService(MysticCore core, PlayerVaultRedisBridge redisBridge, PlayerVaultConfig config) {
        this.core = core;
        this.redisBridge = redisBridge;
        this.config = config;
    }

    public void updateConfig(PlayerVaultConfig config) {
        this.config = config;
    }

    private static String registryKey(UUID owner, int vaultNumber) {
        return owner + ":" + vaultNumber;
    }

    /**
     * Attempts to reserve a vault for a session. Read-only sessions never take a
     * distributed lock. The returned token must be kept for {@link #renew} and
     * {@link #release}.
     */
    public VaultLockResult acquire(UUID owner, int vaultNumber, UUID viewer, VaultOpenMode mode) {
        String key = registryKey(owner, vaultNumber);

        // Read-only inspection: no edit lock, no exclusivity — many staff may look.
        if (mode.isReadOnly()) {
            return VaultLockResult.acquired("readonly-" + VaultLock.newToken());
        }

        // Cross-server enabled but Redis down: fail safe when required.
        if (redisBridge.isDegraded() && config.crossServer.requireRedis) {
            return VaultLockResult.redisUnavailable();
        }

        // Local exclusivity first: cheap, and stops same-server double opens.
        boolean lockDistributed = redisBridge.isActive() && config.crossServer.lockVaults;
        String token = VaultLock.newToken();
        VaultLock lock = new VaultLock(redisBridge.serverId(), viewer, owner, vaultNumber,
                mode.name(), config.crossServer.lockTtlSeconds, token);
        String payload = Json.toString(lock);
        LocalSession session = new LocalSession(token, payload, mode);

        if (localSessions.putIfAbsent(key, session) != null) {
            return VaultLockResult.lockedLocally();
        }

        if (!lockDistributed) {
            // Local-only mode: the registry alone enforces single-writer.
            return VaultLockResult.acquired(token);
        }

        boolean acquired = core.redis().lockAcquire(redisBridge.lockKey(owner, vaultNumber),
                payload, config.crossServer.lockTtlSeconds);
        if (acquired) {
            return VaultLockResult.acquired(token);
        }

        // Lost the distributed race: release our local slot and report the holder.
        localSessions.remove(key, session);
        VaultLock holder = peek(owner, vaultNumber).orElse(null);
        return VaultLockResult.lockedElsewhere(holder);
    }

    /** Renews the distributed lock TTL for a held token. @return {@code true} if still held. */
    public boolean renew(UUID owner, int vaultNumber, String token) {
        String key = registryKey(owner, vaultNumber);
        LocalSession session = localSessions.get(key);
        if (session == null || !session.token.equals(token)) {
            return false;
        }
        if (session.mode.isReadOnly() || !redisBridge.isActive() || !config.crossServer.lockVaults) {
            return true; // no distributed lock to renew
        }
        return core.redis().lockRenew(redisBridge.lockKey(owner, vaultNumber),
                session.payload, config.crossServer.lockTtlSeconds);
    }

    /** Releases a held lock, guarded by the token that acquired it. */
    public void release(UUID owner, int vaultNumber, String token) {
        String key = registryKey(owner, vaultNumber);
        LocalSession session = localSessions.get(key);
        if (session == null || !session.token.equals(token)) {
            return;
        }
        localSessions.remove(key, session);
        if (session.mode.isReadOnly() || !redisBridge.isActive() || !config.crossServer.lockVaults) {
            return;
        }
        try {
            core.redis().lockRelease(redisBridge.lockKey(owner, vaultNumber), session.payload);
        } catch (Throwable t) {
            core.log(Level.WARNING, "[playervaults] lock release failed for " + key + ": " + t);
        }
    }

    /** Admin force-unlock: drops both the local slot and the distributed lock unconditionally. */
    public boolean forceRelease(UUID owner, int vaultNumber) {
        String key = registryKey(owner, vaultNumber);
        LocalSession removed = localSessions.remove(key);
        boolean distributed = false;
        if (redisBridge.isActive()) {
            distributed = core.redis().lockForceRelease(redisBridge.lockKey(owner, vaultNumber));
        }
        return removed != null || distributed;
    }

    /** @return the current lock holder for display, from Redis or (fallback) the local session. */
    public Optional<VaultLock> peek(UUID owner, int vaultNumber) {
        if (redisBridge.isActive()) {
            String raw = core.redis().lockPeek(redisBridge.lockKey(owner, vaultNumber));
            if (raw != null && !raw.isBlank()) {
                try {
                    return Optional.ofNullable(Json.gson().fromJson(raw, VaultLock.class));
                } catch (Throwable ignored) {
                    // fall through to local
                }
            }
        }
        LocalSession session = localSessions.get(registryKey(owner, vaultNumber));
        if (session == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Json.gson().fromJson(session.payload, VaultLock.class));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** @return {@code true} if the given token still owns the local session for this vault. */
    public boolean stillHeld(UUID owner, int vaultNumber, String token) {
        LocalSession session = localSessions.get(registryKey(owner, vaultNumber));
        return session != null && session.token.equals(token);
    }

    /** Releases every local session (module shutdown); distributed locks lapse via TTL. */
    public void releaseAllLocal() {
        localSessions.forEach((key, session) -> {
            if (!session.mode.isReadOnly() && redisBridge.isActive() && config.crossServer.lockVaults) {
                try {
                    String[] parts = key.split(":");
                    UUID owner = UUID.fromString(parts[0]);
                    int vaultNumber = Integer.parseInt(parts[1]);
                    core.redis().lockRelease(redisBridge.lockKey(owner, vaultNumber), session.payload);
                } catch (Throwable ignored) {
                    // TTL will reclaim it.
                }
            }
        });
        localSessions.clear();
    }

    private record LocalSession(String token, String payload, VaultOpenMode mode) {
    }
}
