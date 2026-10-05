package org.hyzionstudios.mysticessentials.core.profile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;
import org.hyzionstudios.mysticessentials.api.service.PlayerProfileService;
import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonElement;

/**
 * Default {@link PlayerProfileService}. Profiles are cached in memory while a
 * player is online and persisted through the {@link StorageService} under the
 * {@code players} namespace.
 *
 * <p>There is exactly one {@link PlayerProfile} instance per player: concurrent
 * {@link #load} calls (several join listeners call it) share one in-flight load,
 * so a change made through one caller's copy is never overwritten by another
 * copy.</p>
 */
public final class PlayerProfileServiceImpl implements PlayerProfileService {

    private static final String NAMESPACE = "players";
    private static final String NAME_INDEX = "usernames";

    private final MysticCore core;
    private final Map<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<PlayerProfile>> loading = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();

    public PlayerProfileServiceImpl(MysticCore core) {
        this.core = core;
    }

    @Override
    public Optional<PlayerProfile> getCached(UUID uuid) {
        return Optional.ofNullable(cache.get(uuid));
    }

    @Override
    public CompletableFuture<PlayerProfile> load(UUID uuid, String username) {
        indexName(uuid, username);
        PlayerProfile cached = cache.get(uuid);
        if (cached != null) {
            cached.setUsername(username);
            cached.setLastJoinDate(Instant.now().toString());
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<PlayerProfile> created = new CompletableFuture<>();
        CompletableFuture<PlayerProfile> inFlight = loading.putIfAbsent(uuid, created);
        if (inFlight != null) {
            return inFlight;
        }
        StorageService storage = core.getStorageService();
        storage.load(NAMESPACE, uuid.toString()).whenComplete((element, failure) -> {
            if (failure != null) {
                loading.remove(uuid, created);
                core.log(Level.SEVERE, "Failed to load the profile of " + username + " (" + uuid + ")",
                        failure);
                created.completeExceptionally(failure);
                return;
            }
            PlayerProfile profile;
            try {
                profile = cache.computeIfAbsent(uuid, id -> fromStorage(element, id, username));
            } catch (RuntimeException e) {
                loading.remove(uuid, created);
                core.log(Level.SEVERE, "Unreadable profile for " + username + " (" + uuid + ")", e);
                created.completeExceptionally(e);
                return;
            }
            loading.remove(uuid, created);
            created.complete(profile);
        });
        return created;
    }

    /** Records a username -> UUID mapping in memory and (persistently) in storage. */
    private void indexName(UUID uuid, String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String key = username.toLowerCase(Locale.ROOT);
        nameIndex.put(key, uuid);
        core.getStorageService().save(NAME_INDEX, key, Json.toTree(uuid.toString()));
    }

    @Override
    public CompletableFuture<Optional<UUID>> resolveUuid(String username) {
        if (username == null || username.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // Online player wins (authoritative, current name).
        Optional<UUID> online = core.platform().findPlayerByName(username).map(ref -> ref.getUuid());
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(online);
        }
        String key = username.toLowerCase(Locale.ROOT);
        UUID cached = nameIndex.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        return core.getStorageService().load(NAME_INDEX, key).thenApply(element -> {
            if (element == null || !element.isJsonPrimitive()) {
                return Optional.<UUID>empty();
            }
            try {
                UUID uuid = UUID.fromString(element.getAsString());
                nameIndex.put(key, uuid);
                return Optional.of(uuid);
            } catch (IllegalArgumentException e) {
                return Optional.<UUID>empty();
            }
        }).exceptionally(failure -> Optional.empty()); // e.g. a name that cannot be a storage key
    }

    @Override
    public CompletableFuture<List<UUID>> knownPlayerUuids() {
        return core.getStorageService().listKeys(NAMESPACE).thenApply(keys -> {
            LinkedHashSet<UUID> ids = new LinkedHashSet<>(cache.keySet());
            for (String key : keys) {
                try {
                    ids.add(UUID.fromString(key));
                } catch (IllegalArgumentException ignored) {
                    // Skip non-UUID keys defensively.
                }
            }
            return new ArrayList<>(ids);
        });
    }

    private PlayerProfile fromStorage(JsonElement element, UUID uuid, String username) {
        if (element == null) {
            return PlayerProfile.create(uuid, username);
        }
        PlayerProfile profile = Json.fromJson(element, PlayerProfile.class);
        if (profile == null) {
            return PlayerProfile.create(uuid, username);
        }
        profile.setUsername(username);
        profile.setLastJoinDate(Instant.now().toString());
        return profile;
    }

    @Override
    public CompletableFuture<Void> save(PlayerProfile profile) {
        JsonElement tree;
        try {
            tree = snapshot(profile);
        } catch (RuntimeException e) {
            core.log(Level.SEVERE, "Could not serialize the profile of " + profile.getUuid(), e);
            return CompletableFuture.failedFuture(e);
        }
        return core.getStorageService().save(NAMESPACE, profile.getUuid().toString(), tree)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        core.log(Level.SEVERE, "Failed to save the profile of " + profile.getUsername()
                                + " (" + profile.getUuid() + ")", failure);
                    }
                });
    }

    /**
     * Serializes a profile. Modules update {@code moduleData} from their own
     * threads, so a concurrent change can interrupt the walk; it is retried
     * rather than failing the save.
     */
    private static JsonElement snapshot(PlayerProfile profile) {
        for (int attempt = 1; ; attempt++) {
            try {
                return Json.toTree(profile);
            } catch (ConcurrentModificationException e) {
                if (attempt >= 5) {
                    throw e;
                }
                Thread.onSpinWait();
            }
        }
    }

    @Override
    public CompletableFuture<Void> unload(UUID uuid) {
        CompletableFuture<PlayerProfile> inFlight = loading.get(uuid);
        if (inFlight != null) {
            // Quit before the join load finished: unload what it produces, or the
            // profile would stay cached (and later be saved stale) for good.
            return inFlight.handle((profile, failure) -> null).thenCompose(ignored -> unload(uuid));
        }
        PlayerProfile profile = cache.remove(uuid);
        if (profile == null) {
            return CompletableFuture.completedFuture(null);
        }
        profile.setLastQuitDate(Instant.now().toString());
        // A rejoin meanwhile reads storage after this save (operations on one key
        // are ordered). A failed save puts the profile back, so the shutdown
        // saveAll (or the next quit) retries it instead of losing the session.
        return save(profile).whenComplete((ignored, failure) -> {
            if (failure != null) {
                cache.putIfAbsent(uuid, profile);
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveAll() {
        CompletableFuture<?>[] futures = cache.values().stream()
                .map(this::save)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }
}
