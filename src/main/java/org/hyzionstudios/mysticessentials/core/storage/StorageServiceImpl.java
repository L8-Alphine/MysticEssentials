package org.hyzionstudios.mysticessentials.core.storage;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.config.MainConfig;

import com.google.gson.JsonElement;

/**
 * {@link StorageService} facade selecting and delegating to the configured
 * {@link StorageProvider}. Selection happens once at start based on
 * {@code storage.provider}; if a non-JSON provider fails to initialize, the Core
 * falls back to JSON so the server still boots.
 */
public final class StorageServiceImpl implements StorageService {

    private final MysticCore core;
    private StorageProvider provider;

    public StorageServiceImpl(MysticCore core) {
        this.core = core;
    }

    public void init(MainConfig config) {
        String requested = config.storage.provider;
        StorageProvider selected = createProvider(requested, config);
        try {
            selected.init();
            core.log(Level.INFO, "Storage provider active: " + selected.id());
        } catch (Exception e) {
            core.log(Level.SEVERE, "Storage provider '" + requested + "' failed to initialize ("
                    + e.getMessage() + "); falling back to JSON.");
            selected = new JsonStorageProvider(core, core.paths().dataDir());
            try {
                selected.init();
            } catch (Exception fatal) {
                throw new IllegalStateException("JSON storage fallback also failed to initialize", fatal);
            }
        }
        this.provider = selected;
    }

    private StorageProvider createProvider(String requested, MainConfig config) {
        return switch (requested) {
            case "mysql", "mariadb" -> new SqlStorageProvider(core, config.storage.mysql, requested);
            case "redis" -> {
                core.log(Level.WARNING, "Redis is a cache/pub-sub layer, not a primary datastore; "
                        + "using JSON for durable storage.");
                yield new JsonStorageProvider(core, core.paths().dataDir());
            }
            default -> new JsonStorageProvider(core, core.paths().dataDir());
        };
    }

    @Override
    public String activeProvider() {
        return provider == null ? "none" : provider.id();
    }

    @Override
    public CompletableFuture<JsonElement> load(String namespace, String key) {
        return provider.load(namespace, key);
    }

    @Override
    public CompletableFuture<Void> save(String namespace, String key, JsonElement value) {
        return provider.save(namespace, key, value);
    }

    @Override
    public CompletableFuture<Boolean> delete(String namespace, String key) {
        return provider.delete(namespace, key);
    }

    @Override
    public CompletableFuture<Boolean> exists(String namespace, String key) {
        return provider.exists(namespace, key);
    }

    @Override
    public CompletableFuture<java.util.List<String>> listKeys(String namespace) {
        return provider.listKeys(namespace);
    }

    public void shutdown() {
        if (provider != null) {
            provider.shutdown();
        }
    }
}
