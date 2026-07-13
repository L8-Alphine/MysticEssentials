package org.hyzionstudios.mysticessentials.modules.playervaults.storage;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.storage.RedisBridge;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;

import com.google.gson.JsonObject;

/**
 * Vault-specific view over the Core {@link RedisBridge}: the short-lived vault
 * cache, the cross-server update pub/sub channel, and the distributed-lock key
 * layout. Every method fails safe when cross-server mode is off or Redis is
 * unavailable — the module then runs local-only with the database as truth.
 *
 * <p><b>Anti-stale-cache stance:</b> the cache only accelerates reads. The
 * authoritative version used for a versioned save is always re-read from
 * permanent storage, never from here, and a pub/sub {@code updates} notice
 * invalidates the cache on peer servers the moment a vault is written elsewhere.</p>
 */
public final class PlayerVaultRedisBridge {

    /** Literal Redis lock key, per the module spec (not network-prefixed). */
    private static final String LOCK_KEY_PREFIX = "mysticessentials:vaults:lock:";
    /** Logical cache-key prefix (the Core bridge adds the network namespace). */
    private static final String CACHE_PREFIX = "vaults:v:";

    private final MysticCore core;
    private PlayerVaultConfig config;

    public PlayerVaultRedisBridge(MysticCore core, PlayerVaultConfig config) {
        this.core = core;
        this.config = config;
    }

    public void updateConfig(PlayerVaultConfig config) {
        this.config = config;
    }

    private RedisBridge redis() {
        return core.redis();
    }

    /** @return {@code true} when cross-server mode is enabled and Redis is actually connected. */
    public boolean isActive() {
        return config.crossServer.enabled && redis() != null && redis().isEnabled();
    }

    /**
     * @return {@code true} if cross-server mode is enabled in config but Redis is
     *         not connected — with {@code requireRedis} this is a hard-stop
     *         condition the module must refuse vault opens on.
     */
    public boolean isDegraded() {
        return config.crossServer.enabled && (redis() == null || !redis().isEnabled());
    }

    public String serverId() {
        RedisBridge bridge = redis();
        String id = bridge == null ? null : bridge.serverId();
        return id == null ? "local" : id;
    }

    // ----- Lock key -------------------------------------------------------------

    /** {@code mysticessentials:vaults:lock:<owner_uuid>:<vault_number>}. */
    public String lockKey(UUID owner, int vaultNumber) {
        return LOCK_KEY_PREFIX + owner + ":" + vaultNumber;
    }

    // ----- Cache ----------------------------------------------------------------

    private String cacheKey(UUID owner, int vaultNumber) {
        return CACHE_PREFIX + owner + ":" + vaultNumber;
    }

    /** Caches a serialized vault with the configured TTL. No-op if inactive. */
    public void cacheVault(PlayerVault vault) {
        if (!isActive() || vault == null) {
            return;
        }
        UUID owner = UUID.fromString(vault.ownerUuid);
        redis().cacheSet(cacheKey(owner, vault.vaultNumber), Json.toString(vault),
                Math.max(1, config.crossServer.cacheTtlSeconds));
    }

    /** @return a cached vault copy if present and parseable, else empty. */
    public Optional<PlayerVault> getCachedVault(UUID owner, int vaultNumber) {
        if (!isActive()) {
            return Optional.empty();
        }
        String raw = redis().cacheGet(cacheKey(owner, vaultNumber));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Json.gson().fromJson(raw, PlayerVault.class));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public void invalidate(UUID owner, int vaultNumber) {
        if (isActive()) {
            redis().cacheDelete(cacheKey(owner, vaultNumber));
        }
    }

    // ----- Pub/sub --------------------------------------------------------------

    /**
     * Publishes a cross-server "vault updated" notice so peers drop their cached
     * copy. The Core bridge tags the message with this server's id and ignores
     * our own echo, so we never invalidate our just-written cache needlessly.
     */
    public void publishUpdate(UUID owner, int vaultNumber, long newVersion) {
        if (!isActive()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("owner", owner.toString());
        payload.addProperty("vault", vaultNumber);
        payload.addProperty("version", newVersion);
        redis().publish(config.crossServer.pubSubChannel, Json.toString(payload));
    }

    /** Subscribes to cross-server vault-update notices. Payload is the JSON published above. */
    public void subscribeUpdates(Consumer<UpdateNotice> handler) {
        if (redis() == null) {
            return;
        }
        redis().subscribe(config.crossServer.pubSubChannel, raw -> {
            try {
                JsonObject payload = Json.asObject(Json.parse(raw));
                UUID owner = UUID.fromString(payload.get("owner").getAsString());
                int vault = payload.get("vault").getAsInt();
                long version = payload.has("version") ? payload.get("version").getAsLong() : 0L;
                handler.accept(new UpdateNotice(owner, vault, version));
            } catch (Throwable ignored) {
                // Malformed notice; nothing to do.
            }
        });
    }

    /** A cross-server notification that a vault was written on another server. */
    public record UpdateNotice(UUID owner, int vaultNumber, long newVersion) {
    }
}
