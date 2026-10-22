package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;

/**
 * The network authority of LootBox openings (P4-WP1/WP2): the shared SQL store
 * holds the truth ({@code lootbox_player}, {@code lootbox_serial},
 * {@code lootbox_open}, {@code lootbox_rev}), Redis holds one lock per player,
 * and every acquisition takes a fencing token from the shared counter.
 *
 * <p>{@link #withLease} runs one unit of work (an opening, a recovery) while
 * this server holds the player's lock: the token is published to
 * {@link LootBoxStorage#fences} for the duration, the lock is renewed in the
 * background, and it is released afterwards. Redis calls block, so they run on
 * {@code executor}, never on the world thread or a lane's caller.</p>
 *
 * <p>Engine-free: the Redis lock and the clock are interfaces, so
 * {@code verifyLootBoxNetwork} runs two "servers" against one fake store and one
 * fake lock.</p>
 */
public final class NetworkAuthority {

    /** The distributed lock; {@code RedisBridge}'s lock methods in production. */
    public interface Lock {
        /** Whether the lock service is reachable now. */
        boolean available();

        boolean acquire(String key, String payload, long ttlSeconds);

        boolean renew(String key, String payload, long ttlSeconds);

        boolean release(String key, String payload);
    }

    /** Why a network unit of work did not run. Nothing was read for spending or written. */
    public static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unavailable(String reason) {
            super(reason);
        }
    }

    /** A held lease: the lock {@code key}/{@code payload} and the fencing token. */
    public record Lease(UUID player, long fence, String key, String payload) {
    }

    /** Lock timings, from {@code network} in the module config. */
    public record Timing(long ttlSeconds, long renewSeconds, long acquireTimeoutMs, long retryMs) {
    }

    private static final SecureRandom TOKENS = new SecureRandom();

    private final LootBoxStorage storage;
    private final Lock lock;
    private final Executor executor;
    private final ScheduledExecutorService renewals;
    private final Supplier<Timing> timing;
    private final BooleanSupplier enabled;
    private final Supplier<String> networkId;
    private final Supplier<String> serverId;
    private final LongSupplier clock;
    /** Fencing tokens of the leases this server holds, by player; read by the storage on every write. */
    private final Map<UUID, Long> held = new ConcurrentHashMap<>();
    private volatile boolean secretAgreed = true;

    public NetworkAuthority(LootBoxStorage storage, Lock lock, Executor executor, ScheduledExecutorService renewals,
            Supplier<Timing> timing, BooleanSupplier enabled, Supplier<String> networkId, Supplier<String> serverId,
            LongSupplier clock) {
        this.storage = storage;
        this.lock = lock;
        this.executor = executor;
        this.renewals = renewals;
        this.timing = timing;
        this.enabled = enabled;
        this.networkId = networkId;
        this.serverId = serverId;
        this.clock = clock;
        storage.fences(player -> held.getOrDefault(player, 0L));
    }

    /** Recorded at enable: whether this server's key-signing secret matches the network's. */
    public void secretAgreed(boolean agreed) {
        this.secretAgreed = agreed;
    }

    public boolean enabled() {
        return enabled.getAsBoolean();
    }

    /** Why NETWORK openings can't run now, or {@code null}. */
    public String unavailable() {
        boolean on = enabled.getAsBoolean();
        boolean redis;
        try {
            redis = on && lock.available();
        } catch (RuntimeException e) {
            redis = false;
        }
        return NetworkRules.unavailable(on, redis, on && storage.sharedAcrossServers(), secretAgreed);
    }

    /** The fencing token this server holds for {@code player}, or 0. */
    public long fenceOf(UUID player) {
        return held.getOrDefault(player, 0L);
    }

    /**
     * Runs {@code work} while holding {@code player}'s network lock. Completes
     * exceptionally with {@link Unavailable} (before {@code work} runs) when the
     * network is unavailable or another server holds the lock past the acquire
     * timeout.
     */
    public <T> CompletableFuture<T> withLease(UUID player, Function<Lease, CompletableFuture<T>> work) {
        String reason = unavailable();
        if (reason != null) {
            return CompletableFuture.failedFuture(new Unavailable(reason));
        }
        return acquire(player).thenCompose(lease -> {
            ScheduledFuture<?> renewal = scheduleRenewal(lease);
            CompletableFuture<T> result;
            try {
                result = work.apply(lease);
            } catch (RuntimeException e) {
                result = CompletableFuture.failedFuture(e);
            }
            return result.whenComplete((value, error) -> {
                if (renewal != null) {
                    renewal.cancel(false);
                }
                release(lease);
            });
        });
    }

    private CompletableFuture<Lease> acquire(UUID player) {
        Timing t = timing.get();
        String key = NetworkRules.lockKey(networkId.get(), player);
        String payload = serverId.get() + ":" + HexFormat.of().formatHex(randomBytes());
        return CompletableFuture.supplyAsync(() -> {
            long deadline = clock.getAsLong() + Math.max(0, t.acquireTimeoutMs());
            while (true) {
                if (lock.acquire(key, payload, t.ttlSeconds())) {
                    return true;
                }
                if (!lock.available()) {
                    throw new Unavailable("the network lock (Redis) is unreachable");
                }
                if (clock.getAsLong() >= deadline) {
                    throw new Unavailable("another server is still finishing this player's opening");
                }
                sleep(t.retryMs());
            }
        }, executor).thenCompose(locked -> storage.nextFence().handle((fence, error) -> {
            if (error != null) {
                lock.release(key, payload);
                throw new CompletionException(new Unavailable("the shared store did not hand out a fencing token: "
                        + rootMessage(error)));
            }
            held.put(player, fence);
            return new Lease(player, fence, key, payload);
        }));
    }

    private ScheduledFuture<?> scheduleRenewal(Lease lease) {
        Timing t = timing.get();
        if (renewals == null || t.renewSeconds() <= 0) {
            return null;
        }
        // A failed renewal needs no action: if another server takes the lock it holds a
        // higher token, and this lease's later writes are refused by the fence.
        return renewals.scheduleAtFixedRate(() -> {
            try {
                lock.renew(lease.key(), lease.payload(), t.ttlSeconds());
            } catch (RuntimeException ignored) {
                // Fencing covers it.
            }
        }, t.renewSeconds(), t.renewSeconds(), TimeUnit.SECONDS);
    }

    private void release(Lease lease) {
        held.remove(lease.player(), lease.fence());
        executor.execute(() -> {
            try {
                lock.release(lease.key(), lease.payload());
            } catch (RuntimeException ignored) {
                // The TTL frees it.
            }
        });
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[12];
        TOKENS.nextBytes(bytes);
        return bytes;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(1, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("interrupted while waiting for the network lock");
        }
    }

    static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /** Whether {@code error} is (or wraps) {@link Unavailable}. */
    public static Unavailable unavailableIn(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof Unavailable unavailable) {
                return unavailable;
            }
        }
        return null;
    }
}
