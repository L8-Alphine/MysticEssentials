package org.hyzionstudios.mysticessentials.api.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

import com.google.gson.JsonElement;

/**
 * Provider-abstracted storage. Modules request namespaced document storage here
 * instead of writing files directly, so the same code works whether the active
 * provider is JSON, MySQL/MariaDB, or (as a cache) Redis.
 *
 * <p>A "namespace" maps to a table/collection/folder (e.g. {@code "mail"}); a
 * "key" is a document id within it (e.g. a player UUID or mail id).</p>
 */
public interface StorageService {

    /** Identifier of the active backing provider (e.g. {@code "json"}). */
    String activeProvider();

    /** Loads a stored JSON document, or {@code null} if absent. */
    CompletableFuture<JsonElement> load(String namespace, String key);

    /** Stores (or replaces) a JSON document. */
    CompletableFuture<Void> save(String namespace, String key, JsonElement value);

    /** Deletes a document. @return {@code true} if it existed. */
    CompletableFuture<Boolean> delete(String namespace, String key);

    /** @return {@code true} if a document exists for the key. */
    CompletableFuture<Boolean> exists(String namespace, String key);

    /** Lists every key stored under {@code namespace} (empty if none). */
    CompletableFuture<List<String>> listKeys(String namespace);

    /**
     * Read-modify-write of one document: loads it ({@code null} if absent), passes
     * it to {@code updater} and stores the result. The built-in service runs the
     * whole cycle after every earlier operation on the same key and before any
     * later one, so concurrent updates on this server never overwrite each other.
     * An updater returning {@code null} leaves the document untouched.
     *
     * @return the stored document (or the unchanged one when the updater returned {@code null})
     */
    default CompletableFuture<JsonElement> update(String namespace, String key,
            UnaryOperator<JsonElement> updater) {
        return load(namespace, key).thenCompose(current -> {
            JsonElement next = updater.apply(current);
            return next == null
                    ? CompletableFuture.completedFuture(current)
                    : save(namespace, key, next).thenApply(ignored -> next);
        });
    }
}
