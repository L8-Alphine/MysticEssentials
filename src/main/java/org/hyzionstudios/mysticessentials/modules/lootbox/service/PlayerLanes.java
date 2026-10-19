package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One ordered lane per player: every opening, key debit, grant and inbox claim
 * for a player runs after the previous one finished, whatever thread asked. With
 * the per-document ordering of {@code StorageService.update} this removes races
 * on one server without holding a lock across storage or world-thread hops.
 */
public final class PlayerLanes {

    private final Map<UUID, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    /** Queues {@code work} behind the player's previous work; a failure never blocks the lane. */
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> run(UUID player, Supplier<CompletableFuture<T>> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<Void>[] created = new CompletableFuture[1];
        tails.compute(player, (uuid, tail) -> {
            CompletableFuture<Void> previous = tail == null ? CompletableFuture.completedFuture(null) : tail;
            CompletableFuture<Void> next = previous.thenCompose(ignored -> {
                CompletableFuture<T> started;
                try {
                    started = work.get();
                    if (started == null) {
                        started = CompletableFuture.completedFuture(null);
                    }
                } catch (Throwable t) {
                    started = CompletableFuture.failedFuture(t);
                }
                return started.handle((value, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(value);
                    }
                    return null;
                });
            });
            created[0] = next;
            return next;
        });
        CompletableFuture<Void> next = created[0];
        next.whenComplete((ignored, error) -> tails.remove(player, next));
        return result;
    }
}
