package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Persistence for the Custom Commands module, built on the Mystic Essentials
 * storage abstraction — so cooldowns and usage stats live in JSON files, or in
 * MySQL/MariaDB when the server uses shared SQL storage (which also makes
 * persisted cooldowns network-wide automatically). Documents:
 *
 * <ul>
 *   <li>{@code customcommands / cooldown-<uuid>} — {@code {commandName: expiryEpochMillis}}</li>
 *   <li>{@code customcommands / usage-stats} — {@code {commandName: totalUses}}</li>
 * </ul>
 */
public final class CustomCommandStorage {

    /** Storage namespace (maps to a folder/table via the active provider). */
    public static final String NAMESPACE = "customcommands";
    private static final String USAGE_KEY = "usage-stats";

    private final MysticCore core;

    public CustomCommandStorage(MysticCore core) {
        this.core = core;
    }

    // ----- Cooldowns -----------------------------------------------------------

    /** Loads a player's persisted cooldown expiries; expired entries are dropped. */
    public CompletableFuture<Map<String, Long>> loadCooldowns(UUID player) {
        return core.getStorageService().load(NAMESPACE, "cooldown-" + player)
                .thenApply(CustomCommandStorage::toLongMap)
                .exceptionally(t -> {
                    core.log(Level.WARNING, "[customcommands] Failed to load cooldowns for "
                            + player + ": " + t);
                    return new LinkedHashMap<>();
                });
    }

    /** Persists a player's cooldown expiries (call with expired entries pruned). */
    public CompletableFuture<Void> saveCooldowns(UUID player, Map<String, Long> expiries) {
        if (expiries.isEmpty()) {
            return core.getStorageService().delete(NAMESPACE, "cooldown-" + player).thenApply(v -> null);
        }
        return core.getStorageService().save(NAMESPACE, "cooldown-" + player, toJson(expiries));
    }

    // ----- Usage stats -----------------------------------------------------------

    public CompletableFuture<Map<String, Long>> loadUsageStats() {
        return core.getStorageService().load(NAMESPACE, USAGE_KEY)
                .thenApply(CustomCommandStorage::toLongMap)
                .exceptionally(t -> {
                    core.log(Level.WARNING, "[customcommands] Failed to load usage stats: " + t);
                    return new LinkedHashMap<>();
                });
    }

    public CompletableFuture<Void> saveUsageStats(Map<String, Long> counters) {
        return core.getStorageService().save(NAMESPACE, USAGE_KEY, toJson(counters));
    }

    // ----- Mapping ----------------------------------------------------------------

    private static Map<String, Long> toLongMap(JsonElement element) {
        Map<String, Long> map = new LinkedHashMap<>();
        if (element != null && element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                try {
                    map.put(entry.getKey(), entry.getValue().getAsLong());
                } catch (RuntimeException ignored) {
                    // Skip malformed entries instead of losing the document.
                }
            }
        }
        return map;
    }

    private static JsonObject toJson(Map<String, Long> map) {
        JsonObject json = new JsonObject();
        map.forEach(json::addProperty);
        return json;
    }
}
