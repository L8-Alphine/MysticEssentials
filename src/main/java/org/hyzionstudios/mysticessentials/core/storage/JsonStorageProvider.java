package org.hyzionstudios.mysticessentials.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonElement;

/**
 * Default local storage provider. Each namespace is a folder under
 * {@code data/} and each key is a {@code <key>.json} file inside it. Suitable
 * for testing and small communities; the primary target for the first release.
 *
 * <p>Operations are executed asynchronously on the common pool but file writes
 * for the same key are serialized by the filesystem; callers that need
 * read-modify-write atomicity should coordinate at a higher level.</p>
 */
public final class JsonStorageProvider implements StorageProvider {

    private final MysticCore core;
    private final Path dataRoot;

    public JsonStorageProvider(MysticCore core, Path dataRoot) {
        this.core = core;
        this.dataRoot = dataRoot;
    }

    @Override
    public String id() {
        return "json";
    }

    @Override
    public void init() throws IOException {
        Files.createDirectories(dataRoot);
    }

    private Path fileFor(String namespace, String key) {
        return dataRoot.resolve(namespace).resolve(key + ".json");
    }

    @Override
    public CompletableFuture<JsonElement> load(String namespace, String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Json.readFile(fileFor(namespace, key));
            } catch (IOException e) {
                throw new StorageException("load " + namespace + "/" + key, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> save(String namespace, String key, JsonElement value) {
        return CompletableFuture.runAsync(() -> {
            try {
                Json.writeFile(fileFor(namespace, key), value);
            } catch (IOException e) {
                throw new StorageException("save " + namespace + "/" + key, e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(String namespace, String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Files.deleteIfExists(fileFor(namespace, key));
            } catch (IOException e) {
                throw new StorageException("delete " + namespace + "/" + key, e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> exists(String namespace, String key) {
        return CompletableFuture.supplyAsync(() -> Files.exists(fileFor(namespace, key)));
    }

    @Override
    public void shutdown() {
        // Nothing to release: writes are flushed synchronously per operation.
    }

    /** Unchecked wrapper so async storage failures surface through the future. */
    public static final class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
