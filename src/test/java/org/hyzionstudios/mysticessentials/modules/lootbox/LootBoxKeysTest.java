package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyLog;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;

import com.google.gson.JsonElement;

/**
 * Engine-free checks of the LootBox keys track: the per-key grant history
 * (P2-F2) stays bounded and keeps every entry written at the same time.
 *
 * <p>Storage is the real {@link LootBoxStorage} over an in-memory
 * {@link StorageService} that orders each document's operations the way
 * {@code StorageServiceImpl} does, as in {@code LootBoxSagaTest}.</p>
 */
public final class LootBoxKeysTest {

    private static final String KEY = "mystic:royal";
    private static final long TIMEOUT_SECONDS = 10;

    /** Storage operations run here, so documents really are read and written from several threads. */
    private static final ExecutorService IO = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "keys-io");
        thread.setDaemon(true);
        return thread;
    });

    private LootBoxKeysTest() {
    }

    public static void main(String[] args) {
        try {
            grantHistoryIsBoundedAndNewestFirst();
            concurrentHistoryWritesAreAllKept();
        } finally {
            IO.shutdownNow();
        }
        System.out.println("LootBoxKeysTest: all checks passed.");
    }

    // ----- Grant history (P2-F2) ------------------------------------------------

    private static void grantHistoryIsBoundedAndNewestFirst() {
        KeyLog log = new KeyLog();
        for (int i = 0; i < 150; i++) {
            log.add(entry(KeyLog.GRANT, i), 100);
        }
        require(log.entries.size() == 100, "the history kept " + log.entries.size() + " entries, not 100");
        require(log.entries.get(0).count == 50, "the history dropped the newest entries instead of the oldest");
        List<KeyLog.Entry> newest = log.newest(40);
        require(newest.size() == 40 && newest.get(0).count == 149 && newest.get(39).count == 110,
                "newest(40) is not the 40 newest, newest first");
        require(new KeyLog().newest(10).isEmpty(), "an empty history listed entries");
        log.add(entry(KeyLog.REVOKE, 999), 0);
        require(log.entries.size() == 1 && log.entries.get(0).count == 999, "a bound below 1 did not keep the newest entry");
    }

    private static void concurrentHistoryWritesAreAllKept() {
        MemoryStorage memory = new MemoryStorage();
        LootBoxStorage storage = new LootBoxStorage(() -> memory);
        List<CompletableFuture<KeyLog>> writes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            writes.add(storage.appendKeyLog(KEY, entry(KeyLog.GRANT, i), 100));
        }
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();
        KeyLog log = await(storage.loadKeyLog(KEY));
        require(log.entries.size() == 60, "racing history writes kept " + log.entries.size() + " of 60");
        require(log.entries.stream().map(e -> e.count).distinct().count() == 60, "a racing history write was lost");
        require(await(storage.loadKeyLog("mystic:other")).entries.isEmpty(), "another key's history is not empty");
    }

    // ----- Fixtures -----------------------------------------------------------

    private static KeyLog.Entry entry(String action, int count) {
        KeyLog.Entry entry = new KeyLog.Entry();
        entry.action = action;
        entry.count = count;
        entry.at = count;
        return entry;
    }

    /**
     * {@link StorageService} in memory. Operations run on {@link #IO} and wait for
     * every earlier operation on the same document, as in {@code StorageServiceImpl},
     * and documents are copied in and out so no caller shares the stored tree.
     */
    static final class MemoryStorage implements StorageService {

        private final Map<String, JsonElement> documents = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<Void>> queues = new ConcurrentHashMap<>();

        @Override
        public String activeProvider() {
            return "memory";
        }

        @Override
        public CompletableFuture<JsonElement> load(String namespace, String key) {
            String id = id(namespace, key);
            return inOrder(id, () -> copy(documents.get(id)));
        }

        @Override
        public CompletableFuture<Void> save(String namespace, String key, JsonElement value) {
            String id = id(namespace, key);
            JsonElement stored = value.deepCopy();
            return inOrder(id, () -> {
                documents.put(id, stored);
                return null;
            });
        }

        @Override
        public CompletableFuture<Boolean> delete(String namespace, String key) {
            String id = id(namespace, key);
            return inOrder(id, () -> documents.remove(id) != null);
        }

        @Override
        public CompletableFuture<Boolean> exists(String namespace, String key) {
            String id = id(namespace, key);
            return inOrder(id, () -> documents.containsKey(id));
        }

        @Override
        public CompletableFuture<List<String>> listKeys(String namespace) {
            String prefix = namespace + "/";
            return CompletableFuture.supplyAsync(() -> documents.keySet().stream().filter(id -> id.startsWith(prefix))
                    .map(id -> id.substring(prefix.length())).sorted().toList(), IO);
        }

        @Override
        public CompletableFuture<JsonElement> update(String namespace, String key, UnaryOperator<JsonElement> updater) {
            String id = id(namespace, key);
            return inOrder(id, () -> {
                JsonElement current = copy(documents.get(id));
                JsonElement next = updater.apply(current);
                if (next == null) {
                    return current;
                }
                documents.put(id, next.deepCopy());
                return next;
            });
        }

        private <T> CompletableFuture<T> inOrder(String id, Supplier<T> operation) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            CompletableFuture<Void> previous = queues.put(id, done);
            CompletableFuture<?> before = previous == null ? CompletableFuture.completedFuture(null) : previous;
            CompletableFuture<T> result = before.handle((ignored, failure) -> null)
                    .thenApplyAsync(ignored -> operation.get(), IO);
            result.whenComplete((value, failure) -> {
                done.complete(null);
                queues.remove(id, done);
            });
            return result;
        }

        private static String id(String namespace, String key) {
            return namespace + "/" + key;
        }

        private static JsonElement copy(JsonElement element) {
            return element == null ? null : element.deepCopy();
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("a step failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("a step did not finish within " + TIMEOUT_SECONDS + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
