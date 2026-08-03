package org.hyzionstudios.mysticessentials.core.notification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAction;
import org.hyzionstudios.mysticessentials.api.notification.NotificationCategory;
import org.hyzionstudios.mysticessentials.api.notification.NotificationPriority;
import org.hyzionstudios.mysticessentials.api.notification.NotificationRecord;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Persistence for notification history and preferences, kept in each player's
 * profile under the {@code notifications} module-data key.
 *
 * <p>Living in the profile is what gives history and preferences the property
 * that matters: a player who disconnects mid-restart-warning still finds it
 * waiting, and a player who muted a category stays muted after a reconnect. The
 * in-memory maps are a write-through cache over that profile data, so reads
 * during chat delivery never touch storage.</p>
 */
final class NotificationStore {

    private static final String MODULE_KEY = "notifications";
    private static final String HISTORY_FIELD = "history";
    private static final String PREFERENCES_FIELD = "preferences";

    private final MysticCore core;
    private volatile NotificationConfig config;

    private final Map<UUID, Deque<NotificationRecord>> history = new ConcurrentHashMap<>();
    private final Map<UUID, NotificationPreferences> preferences = new ConcurrentHashMap<>();

    NotificationStore(MysticCore core, NotificationConfig config) {
        this.core = core;
        this.config = config;
    }

    void updateConfig(NotificationConfig config) {
        this.config = config;
    }

    // ----- Preferences ------------------------------------------------------------

    /** This player's preferences, loading them from the profile on first access. */
    NotificationPreferences preferences(UUID player) {
        if (player == null) {
            return new NotificationPreferences();
        }
        return preferences.computeIfAbsent(player, this::loadPreferences);
    }

    private NotificationPreferences loadPreferences(UUID player) {
        JsonObject data = moduleData(player);
        if (data != null && data.has(PREFERENCES_FIELD)) {
            try {
                NotificationPreferences loaded = Json.gson()
                        .fromJson(data.get(PREFERENCES_FIELD), NotificationPreferences.class);
                if (loaded != null) {
                    return loaded.normalized();
                }
            } catch (Exception e) {
                core.log(Level.WARNING, "[notifications] Unreadable preferences for " + player
                        + "; falling back to defaults: " + e.getMessage());
            }
        }
        return new NotificationPreferences();
    }

    /** Persists this player's preferences back into their profile. */
    void savePreferences(UUID player) {
        NotificationPreferences current = preferences.get(player);
        if (current == null) {
            return;
        }
        mutateModuleData(player, data ->
                data.add(PREFERENCES_FIELD, Json.toTree(current)));
    }

    // ----- History -------------------------------------------------------------------

    /** Appends a record, evicting the oldest past the configured cap. */
    void record(UUID player, NotificationRecord record) {
        if (player == null || record == null || !config.history.enabled) {
            return;
        }
        Deque<NotificationRecord> entries = historyFor(player);
        entries.addFirst(record);
        int max = Math.max(1, config.history.maximumPerPlayer);
        while (entries.size() > max) {
            entries.removeLast();
        }
        persistHistory(player);
    }

    /** Unexpired records for a player, newest first. */
    List<NotificationRecord> history(UUID player) {
        Deque<NotificationRecord> entries = historyFor(player);
        boolean pruned = entries.removeIf(this::shouldPrune);
        List<NotificationRecord> out = new ArrayList<>(entries);
        out.sort(Comparator.comparing(NotificationRecord::receivedAt).reversed());
        if (pruned) {
            persistHistory(player);
        }
        return out;
    }

    /**
     * Whether a record has outlived its usefulness. Critical records are kept
     * past expiry when the server asks for it, because "you missed the restart
     * warning" is exactly the case history exists for.
     */
    private boolean shouldPrune(NotificationRecord record) {
        if (!record.isExpired()) {
            return false;
        }
        return !(config.history.persistCritical
                && record.priority() == NotificationPriority.CRITICAL);
    }

    boolean markRead(UUID player, String notificationId) {
        return replace(player, notificationId, record -> record.read() ? null : record.withRead(true));
    }

    int markAllRead(UUID player) {
        Deque<NotificationRecord> entries = historyFor(player);
        List<NotificationRecord> updated = new ArrayList<>(entries.size());
        int changed = 0;
        for (NotificationRecord record : entries) {
            if (record.read()) {
                updated.add(record);
            } else {
                updated.add(record.withRead(true));
                changed++;
            }
        }
        if (changed > 0) {
            entries.clear();
            entries.addAll(updated);
            persistHistory(player);
        }
        return changed;
    }

    boolean dismiss(UUID player, String notificationId) {
        Deque<NotificationRecord> entries = historyFor(player);
        boolean removed = entries.removeIf(record -> record.id().equals(notificationId));
        if (removed) {
            persistHistory(player);
        }
        return removed;
    }

    Optional<NotificationRecord> find(UUID player, String notificationId) {
        return historyFor(player).stream()
                .filter(record -> record.id().equals(notificationId))
                .findFirst();
    }

    /** Drops a player's cached state once they disconnect and it is persisted. */
    void unload(UUID player) {
        savePreferences(player);
        persistHistory(player);
        history.remove(player);
        preferences.remove(player);
    }

    private boolean replace(UUID player, String notificationId,
            java.util.function.UnaryOperator<NotificationRecord> mapper) {
        Deque<NotificationRecord> entries = historyFor(player);
        List<NotificationRecord> updated = new ArrayList<>(entries.size());
        boolean changed = false;
        for (NotificationRecord record : entries) {
            if (!record.id().equals(notificationId)) {
                updated.add(record);
                continue;
            }
            NotificationRecord replacement = mapper.apply(record);
            updated.add(replacement == null ? record : replacement);
            changed |= replacement != null;
        }
        if (changed) {
            entries.clear();
            entries.addAll(updated);
            persistHistory(player);
        }
        return changed;
    }

    private Deque<NotificationRecord> historyFor(UUID player) {
        return history.computeIfAbsent(player, this::loadHistory);
    }

    private Deque<NotificationRecord> loadHistory(UUID player) {
        Deque<NotificationRecord> entries = new ConcurrentLinkedDeque<>();
        JsonObject data = moduleData(player);
        if (data == null || !data.has(HISTORY_FIELD) || !data.get(HISTORY_FIELD).isJsonArray()) {
            return entries;
        }
        for (JsonElement element : data.getAsJsonArray(HISTORY_FIELD)) {
            NotificationRecord record = decode(element);
            if (record != null) {
                entries.addLast(record);
            }
        }
        return entries;
    }

    private void persistHistory(UUID player) {
        Deque<NotificationRecord> entries = history.get(player);
        if (entries == null) {
            return;
        }
        JsonArray array = new JsonArray();
        entries.forEach(record -> array.add(encode(record)));
        mutateModuleData(player, data -> data.add(HISTORY_FIELD, array));
    }

    // ----- Serialization ----------------------------------------------------------------

    private static JsonObject encode(NotificationRecord record) {
        JsonObject object = new JsonObject();
        object.addProperty("id", record.id());
        object.addProperty("category", record.category().id());
        object.addProperty("priority", record.priority().id());
        object.addProperty("title", record.title());
        object.addProperty("message", record.message());
        object.addProperty("source", record.source());
        object.addProperty("action", record.action().encode());
        object.addProperty("receivedAt", record.receivedAt().toEpochMilli());
        if (record.expiresAt() != null) {
            object.addProperty("expiresAt", record.expiresAt().toEpochMilli());
        }
        object.addProperty("read", record.read());
        return object;
    }

    private NotificationRecord decode(JsonElement element) {
        try {
            JsonObject object = element.getAsJsonObject();
            return new NotificationRecord(
                    string(object, "id"),
                    NotificationCategory.of(string(object, "category")),
                    NotificationPriority.parse(string(object, "priority")),
                    string(object, "title"),
                    string(object, "message"),
                    string(object, "source"),
                    NotificationAction.decode(string(object, "action")),
                    Instant.ofEpochMilli(object.has("receivedAt")
                            ? object.get("receivedAt").getAsLong() : 0),
                    object.has("expiresAt")
                            ? Instant.ofEpochMilli(object.get("expiresAt").getAsLong()) : null,
                    object.has("read") && object.get("read").getAsBoolean());
        } catch (Exception e) {
            // A malformed entry loses one notification, never the whole history.
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    // ----- Profile access --------------------------------------------------------------

    private JsonObject moduleData(UUID player) {
        return core.getPlayerProfileService().getCached(player)
                .map(profile -> profile.getModuleData().get(MODULE_KEY))
                .orElse(null);
    }

    /**
     * Applies {@code mutation} to this player's module data and marks the profile
     * dirty. A no-op when the profile is not loaded — an offline player's history
     * is written when their own session ends, not by somebody else's send.
     */
    private void mutateModuleData(UUID player, java.util.function.Consumer<JsonObject> mutation) {
        Optional<PlayerProfile> profile = core.getPlayerProfileService().getCached(player);
        if (profile.isEmpty()) {
            return;
        }
        Map<String, JsonObject> moduleData = profile.get().getModuleData();
        JsonObject data = moduleData.computeIfAbsent(MODULE_KEY, key -> new JsonObject());
        mutation.accept(data);
    }
}
