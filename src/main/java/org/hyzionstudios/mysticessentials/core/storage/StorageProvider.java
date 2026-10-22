package org.hyzionstudios.mysticessentials.core.storage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

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

    /** Lists every key stored under {@code namespace} (empty if none). */
    CompletableFuture<List<String>> listKeys(String namespace);

    /** Whether {@link #updateAtomic} is atomic across every server using this store. */
    default boolean atomicUpdates() {
        return false;
    }

    /**
     * Read-modify-write of one document in one transaction (see
     * {@code StorageService#updateShared}). Only called when
     * {@link #atomicUpdates()} is true.
     */
    default CompletableFuture<JsonElement> updateAtomic(String namespace, String key,
            UnaryOperator<JsonElement> updater) {
        throw new UnsupportedOperationException(id() + " has no atomic updates");
    }

    /** Flushes and closes the provider. Called during Core shutdown. */
    void shutdown();
}
