package org.hyzionstudios.mysticessentials.core.profile;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
 */
public final class PlayerProfileServiceImpl implements PlayerProfileService {

    private static final String NAMESPACE = "players";
    private static final String NAME_INDEX = "usernames";

    private final MysticCore core;
    private final Map<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();
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
        StorageService storage = core.getStorageService();
        return storage.load(NAMESPACE, uuid.toString()).thenApply(element -> {
            PlayerProfile profile = fromStorage(element, uuid, username);
            cache.put(uuid, profile);
            return profile;
        });
    }

    /** Records a username -> UUID mapping in memory and (persistently) in storage. */
    private void indexName(UUID uuid, String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        nameIndex.put(username.toLowerCase(), uuid);
        core.getStorageService().save(NAME_INDEX, username.toLowerCase(), Json.toTree(uuid.toString()));
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
        String key = username.toLowerCase();
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
        });
    }

    @Override
    public CompletableFuture<java.util.List<UUID>> knownPlayerUuids() {
        return core.getStorageService().listKeys(NAMESPACE).thenApply(keys -> {
            java.util.LinkedHashSet<UUID> ids = new java.util.LinkedHashSet<>(cache.keySet());
            for (String key : keys) {
                try {
                    ids.add(UUID.fromString(key));
                } catch (IllegalArgumentException ignored) {
                    // Skip non-UUID keys defensively.
                }
            }
            return new java.util.ArrayList<>(ids);
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
        return core.getStorageService().save(NAMESPACE, profile.getUuid().toString(), Json.toTree(profile));
    }

    @Override
    public CompletableFuture<Void> unload(UUID uuid) {
        PlayerProfile profile = cache.remove(uuid);
        if (profile == null) {
            return CompletableFuture.completedFuture(null);
        }
        profile.setLastQuitDate(Instant.now().toString());
        return save(profile);
    }

    @Override
    public CompletableFuture<Void> saveAll() {
        CompletableFuture<?>[] futures = cache.values().stream()
                .map(this::save)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }
}
