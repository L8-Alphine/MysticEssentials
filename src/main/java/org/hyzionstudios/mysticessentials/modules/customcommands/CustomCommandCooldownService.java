package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonObject;

/**
 * Per-player, per-command cooldown tracking. Checks are served from memory;
 * when {@code cooldowns.persist} is on, expiries are written through the
 * storage abstraction (surviving restarts, and network-wide on shared SQL)
 * and loaded on player join. When Redis is enabled and
 * {@code crossServer.syncCooldowns} is on, cooldown starts are broadcast so
 * every server in the network applies them immediately — the groundwork for
 * full cross-server cooldown sync.
 */
public final class CustomCommandCooldownService {

    /** Redis channel for cooldown-start events (payload: {@code {uuid, command, expiry}}). */
    public static final String REDIS_CHANNEL = "ccmd-cooldown";

    private final MysticCore core;
    private final CustomCommandStorage storage;
    private final Supplier<CustomCommandsConfig> config;

    /** player uuid -> (command name -> expiry epoch millis). */
    private final Map<UUID, Map<String, Long>> expiries = new ConcurrentHashMap<>();

    public CustomCommandCooldownService(MysticCore core, CustomCommandStorage storage,
            Supplier<CustomCommandsConfig> config) {
        this.core = core;
        this.storage = storage;
        this.config = config;
    }

    /** Subscribes to cross-server cooldown events; call once on module enable. */
    public void connectRedis() {
        if (!core.redis().isEnabled() || !config.get().crossServer.syncCooldowns) {
            return;
        }
        core.redis().subscribe(REDIS_CHANNEL, payload -> {
            try {
                JsonObject json = Json.parse(payload).getAsJsonObject();
                applyRemote(UUID.fromString(json.get("uuid").getAsString()),
                        json.get("command").getAsString(),
                        json.get("expiry").getAsLong());
            } catch (RuntimeException e) {
                core.log(Level.WARNING, "[customcommands] Bad cooldown sync payload: " + e.getMessage());
            }
        });
    }

    // ----- Queries --------------------------------------------------------------

    /** @return remaining whole seconds, or {@code 0} when ready. */
    public long remainingSeconds(UUID player, String commandName) {
        if (player == null) {
            return 0;
        }
        Map<String, Long> byCommand = expiries.get(player);
        if (byCommand == null) {
            return 0;
        }
        Long expiry = byCommand.get(commandName);
        if (expiry == null) {
            return 0;
        }
        long remainingMillis = expiry - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            byCommand.remove(commandName);
            return 0;
        }
        return (remainingMillis + 999) / 1000;
    }

    // ----- Mutations ---------------------------------------------------------------

    /** Starts a cooldown, persists it (if configured), and broadcasts it to the network. */
    public void start(UUID player, String commandName, long seconds) {
        if (player == null || seconds <= 0) {
            return;
        }
        long expiry = System.currentTimeMillis() + seconds * 1000L;
        expiries.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(commandName, expiry);
        persist(player);
        publish(player, commandName, expiry);
    }

    /** Clears one command's cooldown for a player (admin/testing convenience). */
    public void clear(UUID player, String commandName) {
        Map<String, Long> byCommand = expiries.get(player);
        if (byCommand != null) {
            byCommand.remove(commandName);
            persist(player);
        }
    }

    private void applyRemote(UUID player, String commandName, long expiry) {
        if (expiry <= System.currentTimeMillis()) {
            return;
        }
        expiries.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(commandName, expiry);
        // No re-persist here: the originating server already wrote it.
    }

    // ----- Player lifecycle -----------------------------------------------------------

    /** Loads persisted cooldowns into memory when a player connects. */
    public void onJoin(UUID player) {
        if (!config.get().cooldowns.persist) {
            return;
        }
        storage.loadCooldowns(player).thenAccept(loaded -> {
            long now = System.currentTimeMillis();
            Map<String, Long> live = new ConcurrentHashMap<>();
            loaded.forEach((command, expiry) -> {
                if (expiry > now) {
                    live.put(command, expiry);
                }
            });
            if (!live.isEmpty()) {
                expiries.merge(player, live, (existing, incoming) -> {
                    incoming.forEach((cmd, exp) -> existing.merge(cmd, exp, Math::max));
                    return existing;
                });
            }
        });
    }

    /** Evicts a player's in-memory state on disconnect (persisted copies remain). */
    public void onQuit(UUID player) {
        expiries.remove(player);
    }

    /** Flushes every online player's cooldowns; called on module disable. */
    public void flushAll() {
        if (!config.get().cooldowns.persist) {
            return;
        }
        expiries.keySet().forEach(this::persist);
    }

    // ----- Write-through -----------------------------------------------------------------

    private void persist(UUID player) {
        if (!config.get().cooldowns.persist) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Long> byCommand = expiries.getOrDefault(player, Map.of());
        Map<String, Long> alive = new LinkedHashMap<>();
        byCommand.forEach((command, expiry) -> {
            if (expiry > now) {
                alive.put(command, expiry);
            }
        });
        storage.saveCooldowns(player, alive);
    }

    private void publish(UUID player, String commandName, long expiry) {
        if (!core.redis().isEnabled() || !config.get().crossServer.syncCooldowns) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", player.toString());
        payload.addProperty("command", commandName);
        payload.addProperty("expiry", expiry);
        core.redis().publish(REDIS_CHANNEL, payload.toString());
    }
}
