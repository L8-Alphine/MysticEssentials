package org.hyzionstudios.mysticessentials.modules.tutorial.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.tutorial.player.TutorialPlayerData;

/**
 * JSON-file storage for tutorial player data:
 * {@code mods/MysticEssentials/data/modules/tutorial/players/<uuid>.json}.
 * Data for online players is cached in memory; a periodic autosave flushes
 * dirty entries and everything is flushed on shutdown. All file I/O runs on
 * the shared Mystic scheduler thread, never on a world thread.
 */
public final class JsonTutorialStorage implements TutorialStorage {

    private final MysticCore core;
    private final String moduleId;

    private final Map<UUID, TutorialPlayerData> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private ScheduledFuture<?> autosaveTask;

    public JsonTutorialStorage(MysticCore core, String moduleId) {
        this.core = core;
        this.moduleId = moduleId;
    }

    private Path playersDir() {
        return core.paths().moduleDataDir(moduleId).resolve("players");
    }

    private Path fileFor(UUID playerId) {
        return playersDir().resolve(playerId + ".json");
    }

    public void start(int autosaveSeconds) {
        int interval = Math.max(10, autosaveSeconds);
        autosaveTask = core.scheduler().runRepeating(() -> saveAll().join(), interval, interval, TimeUnit.SECONDS);
    }

    @Override
    public CompletableFuture<TutorialPlayerData> load(UUID playerId, String username) {
        TutorialPlayerData existing = cache.get(playerId);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        return CompletableFuture.supplyAsync(() -> {
            TutorialPlayerData data = null;
            try {
                data = Json.readFile(fileFor(playerId), TutorialPlayerData.class);
            } catch (Exception e) {
                // A corrupt player file must not crash the server or wipe itself:
                // log, start from fresh in memory, and only overwrite on next save.
                core.log(Level.WARNING, "[" + moduleId + "] Corrupt player file for " + playerId
                        + " (" + e.getMessage() + "); starting fresh in memory.");
            }
            if (data == null) {
                data = new TutorialPlayerData(playerId.toString(), username);
            } else if (username != null && !username.isBlank()) {
                data.username = username;
            }
            TutorialPlayerData raced = cache.putIfAbsent(playerId, data);
            return raced != null ? raced : data;
        });
    }

    @Override
    public Optional<TutorialPlayerData> cached(UUID playerId) {
        return Optional.ofNullable(cache.get(playerId));
    }

    @Override
    public void markDirty(UUID playerId) {
        if (cache.containsKey(playerId)) {
            dirty.add(playerId);
        }
    }

    @Override
    public CompletableFuture<Void> save(UUID playerId) {
        TutorialPlayerData data = cache.get(playerId);
        if (data == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> writeQuietly(playerId, data));
    }

    @Override
    public CompletableFuture<Void> unload(UUID playerId) {
        return save(playerId).whenComplete((v, t) -> {
            cache.remove(playerId);
            dirty.remove(playerId);
        });
    }

    @Override
    public CompletableFuture<Void> saveAll() {
        List<UUID> toSave = List.copyOf(dirty);
        if (toSave.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            for (UUID playerId : toSave) {
                TutorialPlayerData data = cache.get(playerId);
                if (data != null) {
                    writeQuietly(playerId, data);
                }
            }
        });
    }

    private void writeQuietly(UUID playerId, TutorialPlayerData data) {
        try {
            synchronized (data) {
                Json.writeFile(fileFor(playerId), data);
            }
            dirty.remove(playerId);
        } catch (Exception e) {
            core.log(Level.SEVERE, "[" + moduleId + "] Failed to save player data for "
                    + playerId + ": " + e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        if (autosaveTask != null) {
            autosaveTask.cancel(false);
            autosaveTask = null;
        }
        // Flush synchronously: the scheduler is about to go away.
        for (Map.Entry<UUID, TutorialPlayerData> entry : cache.entrySet()) {
            if (dirty.contains(entry.getKey())) {
                writeQuietly(entry.getKey(), entry.getValue());
            }
        }
        cache.clear();
        dirty.clear();
    }
}
