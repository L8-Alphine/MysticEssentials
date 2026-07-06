package org.hyzionstudios.mysticessentials.core.storage;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;

/**
 * Low-level backing store behind the {@code StorageService}. One implementation
 * exists per provider (JSON, MySQL/MariaDB, Redis). Implementations must be
 * safe to call from multiple threads.
 */
public interface StorageProvider {

    /** Provider id as used in {@code config.json} (e.g. {@code "json"}). */
    String id();

    /** Opens connections / creates schema. Called once during Core start. */
    void init() throws Exception;

    CompletableFuture<JsonElement> load(String namespace, String key);

    CompletableFuture<Void> save(String namespace, String key, JsonElement value);

    CompletableFuture<Boolean> delete(String namespace, String key);

    CompletableFuture<Boolean> exists(String namespace, String key);

    /** Flushes and closes the provider. Called during Core shutdown. */
    void shutdown();
}
