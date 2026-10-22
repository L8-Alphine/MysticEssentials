package org.hyzionstudios.mysticessentials.modules.lootbox.integration;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.PendingInbox;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;

/**
 * LootBox placeholders (P3-WP7), through Essentials' placeholder service, so
 * they work as {@code {name}}, {@code %mysticessentials_name%} and, with
 * PlaceholderAPI installed, from other plugins:
 *
 * <ul>
 *   <li>{@code lootbox_keys}: all virtual keys the player holds;</li>
 *   <li>{@code lootbox_keys_<namespace>_<name>}: virtual keys of one key
 *       ({@code mystic:gold} is {@code lootbox_keys_mystic_gold});</li>
 *   <li>{@code lootbox_pending}: rewards waiting in the player's inbox.</li>
 * </ul>
 *
 * <p>Placeholders resolve synchronously, so they answer from a per-player cache
 * that is filled off-thread on join, refreshed after grants, openings and claims,
 * and otherwise at most every {@link #TTL_MS} ms. Key items in inventories are
 * not counted (that would need the world thread). The placeholder service can't
 * unregister: while LootBox is off they answer {@code 0}.</p>
 */
public final class LootBoxPlaceholders {

    static final long TTL_MS = 15_000L;

    private record Snapshot(Map<String, Integer> keys, int total, int pending, long at) {
    }

    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private final Set<String> registered = ConcurrentHashMap.newKeySet();
    private volatile Map<String, String> keyNames = Map.of();
    private volatile LootBoxStorage storage;

    /** Starts answering from {@code storage}; {@code null} stops (module disabled). */
    public void use(LootBoxStorage storage) {
        this.storage = storage;
        cache.clear();
    }

    /**
     * Registers the fixed names and one per published key. Safe to call again
     * when keys are published: only new names are registered.
     */
    public void register(BiFunctionRegistrar registrar, Collection<String> keyIds) {
        if (registered.add("lootbox_keys")) {
            registrar.register("lootbox_keys", (player, name) -> String.valueOf(value(player, null)));
        }
        if (registered.add("lootbox_pending")) {
            registrar.register("lootbox_pending", (player, name) -> {
                Snapshot snapshot = snapshot(player);
                return String.valueOf(snapshot == null ? 0 : snapshot.pending());
            });
        }
        Map<String, String> names = IntegrationRules.keyPlaceholders(keyIds);
        keyNames = names;
        for (String name : names.keySet()) {
            if (registered.add(name)) {
                registrar.register(name, (player, ignored) -> {
                    if (!keyNames.containsKey(name)) {
                        return "0";
                    }
                    String keyId = keyNames.get(name);
                    return keyId == null ? "?" : String.valueOf(value(player, keyId));
                });
            }
        }
    }

    /** The placeholder service's {@code register}, as a functional interface. */
    @FunctionalInterface
    public interface BiFunctionRegistrar {
        void register(String name, BiFunction<UUID, String, String> resolver);
    }

    /** Fills or refreshes one player's cache off-thread (on join, after a grant). */
    public void refresh(UUID player) {
        LootBoxStorage current = storage;
        if (player == null || current == null || !loading.add(player)) {
            return;
        }
        CompletableFuture<PlayerLootState> state = current.loadPlayer(player);
        CompletableFuture<PendingInbox> inbox = current.loadPending(player);
        state.thenCombine(inbox, (loot, pending) -> {
            Map<String, Integer> keys = loot == null || loot.virtualKeys == null ? Map.of()
                    : Map.copyOf(loot.virtualKeys);
            int total = 0;
            for (int count : keys.values()) {
                total += Math.max(0, count);
            }
            int waiting = pending == null || pending.items == null ? 0 : pending.items.size();
            return new Snapshot(keys, total, waiting, System.currentTimeMillis());
        }).whenComplete((snapshot, error) -> {
            loading.remove(player);
            if (snapshot != null && storage == current) {
                cache.put(player, snapshot);
            }
        });
    }

    public void forget(UUID player) {
        if (player != null) {
            cache.remove(player);
        }
    }

    private int value(UUID player, String keyId) {
        Snapshot snapshot = snapshot(player);
        if (snapshot == null) {
            return 0;
        }
        if (keyId == null) {
            return snapshot.total();
        }
        Integer count = snapshot.keys().get(keyId);
        return count == null ? 0 : Math.max(0, count);
    }

    private Snapshot snapshot(UUID player) {
        if (player == null || storage == null) {
            return null;
        }
        Snapshot snapshot = cache.get(player);
        if (snapshot == null || System.currentTimeMillis() - snapshot.at() > TTL_MS) {
            refresh(player);
        }
        return snapshot;
    }
}
