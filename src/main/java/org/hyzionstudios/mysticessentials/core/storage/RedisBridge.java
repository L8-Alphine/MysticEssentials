package org.hyzionstudios.mysticessentials.core.storage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.config.MainConfig;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonObject;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.params.SetParams;

/**
 * Redis integration: a fast cache and a pub/sub bus for cross-server features
 * (broadcasts, private messages, synced temporary state). Redis is treated as a
 * cache/message layer, never the primary datastore.
 *
 * <p>All logical channels and cache keys are namespaced by {@code networkId}, and
 * every published message carries the origin {@code serverId} so a server ignores
 * its own echoes. A single pattern subscription ({@code <networkId>:ch:*}) feeds a
 * dynamic handler map, and the subscriber thread reconnects on failure.</p>
 *
 * <p>Every consumer must fail safely when Redis is disabled or unreachable: check
 * {@link #isEnabled()} first; cross-server features degrade to local-only.</p>
 */
public final class RedisBridge {

    private final MysticCore core;
    private final Map<String, Consumer<String>> handlers = new ConcurrentHashMap<>();

    private MainConfig.Redis config;
    private volatile boolean enabled;
    private RedisClient commands;
    private JedisClientConfig clientConfig;
    private Thread subscriberThread;
    private volatile JedisPubSub subscription;
    private volatile boolean shuttingDown;
    private volatile long lifecycleGeneration;

    public RedisBridge(MysticCore core) {
        this.core = core;
    }

    public synchronized void init(MainConfig.Redis config) {
        lifecycleGeneration++;
        this.config = config;
        if (config == null || !config.enabled) {
            core.log(Level.INFO, "Redis disabled; cross-server features run in local-only mode.");
            return;
        }
        try {
            String username = config.username == null || config.username.isBlank() ? null : config.username;
            String password = config.password == null || config.password.isBlank() ? null : config.password;
            var clientBuilder = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(2000)
                    .socketTimeoutMillis(2000)
                    .clientName("MysticEssentials");
            if (username != null) {
                clientBuilder.user(username);
            }
            if (password != null) {
                clientBuilder.password(password);
            }
            clientConfig = clientBuilder.build();
            commands = RedisClient.builder()
                    .hostAndPort(new HostAndPort(config.host, config.port))
                    .clientConfig(clientConfig)
                    .build();
            commands.ping();
            enabled = true;
            startSubscriber();
            core.log(Level.INFO, "Redis connected at " + config.host + ":" + config.port
                    + " (network=" + config.networkId + ", server=" + config.serverId + ")");
        } catch (LinkageError t) {
            // Jedis is shaded into the release jar; its absence means the thin
            // (unshaded) jar was deployed, not that Redis is down.
            core.log(Level.SEVERE, "Redis enabled but the Redis client library is missing from this jar ("
                    + t + "). Deploy the shaded MysticEssentials-<version>.jar, not the -thin one. "
                    + "Running in local-only mode.");
            enabled = false;
            closeCommands();
        } catch (Throwable t) {
            core.log(Level.SEVERE, "Redis enabled but connection failed (" + t.getMessage()
                    + "); running in local-only mode.");
            enabled = false;
            closeCommands();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Applies new Redis settings without dropping registered logical-channel handlers. */
    public synchronized void reconfigure(MainConfig.Redis config) {
        shutdown();
        shuttingDown = false;
        subscription = null;
        subscriberThread = null;
        init(config);
    }

    public String serverId() {
        return config == null ? null : config.serverId;
    }

    public String networkId() {
        return config == null ? null : config.networkId;
    }

    // ----- Pub/sub -----------------------------------------------------------

    /** Registers a handler for a logical cross-server channel (idempotent per channel). */
    public void subscribe(String channel, Consumer<String> handler) {
        handlers.put(channel, handler);
    }

    /** Removes a logical-channel handler registered by a reloadable module. */
    public void unsubscribe(String channel, Consumer<String> handler) {
        handlers.remove(channel, handler);
    }

    /** Publishes a payload to a logical channel across the network. No-op if Redis is unavailable. */
    public void publish(String channel, String payload) {
        if (!enabled) {
            return;
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("s", config.serverId);
        envelope.addProperty("c", channel);
        envelope.addProperty("m", payload);
        try {
            commands.publish(channelKey(channel), Json.toString(envelope));
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis publish to '" + channel + "' failed: " + t);
        }
    }

    private void startSubscriber() {
        subscription = new JedisPubSub() {
            @Override
            public void onPMessage(String pattern, String channel, String message) {
                dispatch(message);
            }
        };
        long generation = lifecycleGeneration;
        subscriberThread = new Thread(() -> subscriberLoop(generation),
                "MysticEssentials-Redis-Sub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    private void subscriberLoop(long generation) {
        String pattern = channelKey("*");
        while (enabled && !shuttingDown && generation == lifecycleGeneration) {
            try (Jedis jedis = new Jedis(config.host, config.port, clientConfig)) {
                jedis.psubscribe(subscription, pattern); // blocks until punsubscribe
            } catch (Throwable t) {
                if (shuttingDown || generation != lifecycleGeneration) {
                    return;
                }
                core.log(Level.WARNING, "Redis subscriber dropped (" + t.getMessage() + "); reconnecting in 5s.");
                sleep();
            }
        }
    }

    private void dispatch(String rawEnvelope) {
        try {
            JsonObject envelope = Json.asObject(Json.parse(rawEnvelope));
            String origin = envelope.has("s") ? envelope.get("s").getAsString() : null;
            if (origin != null && origin.equals(config.serverId)) {
                return; // ignore our own echo
            }
            String channel = envelope.has("c") ? envelope.get("c").getAsString() : null;
            String payload = envelope.has("m") ? envelope.get("m").getAsString() : "";
            Consumer<String> handler = channel == null ? null : handlers.get(channel);
            if (handler != null) {
                handler.accept(payload);
            }
        } catch (Throwable t) {
            core.log(Level.WARNING, "Failed to handle Redis message: " + t);
        }
    }

    // ----- Cache -------------------------------------------------------------

    /** Reads a cached value, or {@code null} if absent/unavailable. */
    public String cacheGet(String key) {
        if (!enabled) {
            return null;
        }
        try {
            return commands.get(cacheKey(key));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Writes a cached value with a time-to-live in seconds. No-op if unavailable. */
    public void cacheSet(String key, String value, long ttlSeconds) {
        if (!enabled) {
            return;
        }
        try {
            if (ttlSeconds > 0) {
                commands.set(cacheKey(key), value, SetParams.setParams().ex(ttlSeconds));
            } else {
                commands.set(cacheKey(key), value);
            }
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis cacheSet '" + key + "' failed: " + t);
        }
    }

    /** Deletes cached values. No-op if Redis is unavailable. */
    public void cacheDelete(String... keys) {
        if (!enabled || keys == null || keys.length == 0) {
            return;
        }
        try {
            String[] namespaced = new String[keys.length];
            for (int i = 0; i < keys.length; i++) {
                namespaced[i] = cacheKey(keys[i]);
            }
            commands.del(namespaced);
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis cacheDelete failed: " + t);
        }
    }

    /** Adds members to a namespaced Redis set and optionally refreshes its TTL. */
    public boolean cacheSetAdd(String key, int ttlSeconds, String... members) {
        if (!enabled || members == null || members.length == 0) {
            return false;
        }
        try {
            String namespaced = cacheKey(key);
            commands.sadd(namespaced, members);
            if (ttlSeconds > 0) {
                commands.expire(namespaced, ttlSeconds);
            }
            return true;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis cacheSetAdd '" + key + "' failed: " + t);
            return false;
        }
    }

    /** Returns a snapshot of a namespaced Redis set, or an empty set when unavailable. */
    public java.util.Set<String> cacheSetMembers(String key) {
        if (!enabled) {
            return java.util.Set.of();
        }
        try {
            return java.util.Set.copyOf(commands.smembers(cacheKey(key)));
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis cacheSetMembers '" + key + "' failed: " + t);
            return java.util.Set.of();
        }
    }

    /** Removes members from a namespaced Redis set. */
    public void cacheSetRemove(String key, String... members) {
        if (!enabled || members == null || members.length == 0) {
            return;
        }
        try {
            commands.srem(cacheKey(key), members);
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis cacheSetRemove '" + key + "' failed: " + t);
        }
    }

    /**
     * Writes one field of a namespaced Redis hash and refreshes the key's TTL.
     * Fields are the unit of atomicity, so several servers can add entries to
     * the same hash concurrently without a read-modify-write race.
     */
    public boolean cacheHashSet(String key, String field, String value, long ttlSeconds) {
        if (!enabled || field == null || value == null) {
            return false;
        }
        try {
            String namespaced = cacheKey(key);
            commands.hset(namespaced, field, value);
            if (ttlSeconds > 0) {
                commands.expire(namespaced, ttlSeconds);
            }
            return true;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis cacheHashSet '" + key + "' failed: " + t);
            return false;
        }
    }

    /** Snapshot of a namespaced Redis hash, or an empty map when absent/unavailable. */
    public Map<String, String> cacheHashGetAll(String key) {
        if (!enabled) {
            return Map.of();
        }
        try {
            Map<String, String> fields = commands.hgetAll(cacheKey(key));
            return fields == null ? Map.of() : Map.copyOf(fields);
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis cacheHashGetAll '" + key + "' failed: " + t);
            return Map.of();
        }
    }

    /** Removes fields from a namespaced Redis hash. No-op if Redis is unavailable. */
    public void cacheHashRemove(String key, String... fields) {
        if (!enabled || fields == null || fields.length == 0) {
            return;
        }
        try {
            commands.hdel(cacheKey(key), fields);
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis cacheHashRemove '" + key + "' failed: " + t);
        }
    }

    // ----- Distributed locks ---------------------------------------------------

    /**
     * Lua: renew/release only when the stored value still equals the exact
     * payload the caller wrote — the payload embeds a random token, so a lock
     * that expired and was re-acquired by another server can never be touched.
     */
    private static final String LOCK_RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end";
    private static final String LOCK_RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    /**
     * Atomically acquires a distributed lock ({@code SET NX EX}). Lock keys are
     * used <b>as given</b> (not namespace-prefixed) so modules can define
     * network-wide key layouts; callers should embed a unique token in
     * {@code payload} and keep it for renew/release.
     *
     * @return {@code true} if this call created the lock; {@code false} if the
     *         key already exists or Redis is unavailable.
     */
    public boolean lockAcquire(String key, String payload, long ttlSeconds) {
        if (!enabled) {
            return false;
        }
        try {
            String reply = commands.set(key, payload,
                    SetParams.setParams().nx().ex(Math.max(1, ttlSeconds)));
            return "OK".equals(reply);
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis lockAcquire '" + key + "' failed: " + t);
            return false;
        }
    }

    /** @return the raw payload of a held lock, or {@code null} when free/unavailable. */
    public String lockPeek(String key) {
        if (!enabled) {
            return null;
        }
        try {
            return commands.get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Extends a lock's TTL, but only while the stored payload still matches
     * {@code payload} (i.e. we still own it).
     *
     * @return {@code true} if the lock was renewed.
     */
    public boolean lockRenew(String key, String payload, long ttlSeconds) {
        if (!enabled) {
            return false;
        }
        try {
            Object result = commands.eval(LOCK_RENEW_SCRIPT, List.of(key),
                    List.of(payload, Long.toString(Math.max(1, ttlSeconds))));
            return result instanceof Long value && value == 1L;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis lockRenew '" + key + "' failed: " + t);
            return false;
        }
    }

    /**
     * Releases a lock, but only while the stored payload still matches
     * {@code payload}. A lock lost to TTL expiry and re-acquired elsewhere is
     * left untouched.
     *
     * @return {@code true} if this call deleted the lock.
     */
    public boolean lockRelease(String key, String payload) {
        if (!enabled) {
            return false;
        }
        try {
            Object result = commands.eval(LOCK_RELEASE_SCRIPT, List.of(key), List.of(payload));
            return result instanceof Long value && value == 1L;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis lockRelease '" + key + "' failed: " + t);
            return false;
        }
    }

    /** Unconditionally deletes a lock (admin force unlock). @return {@code true} if it existed. */
    public boolean lockForceRelease(String key) {
        if (!enabled) {
            return false;
        }
        try {
            return commands.del(key) > 0;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis lockForceRelease '" + key + "' failed: " + t);
            return false;
        }
    }

    // ----- Lifecycle / helpers ----------------------------------------------

    public synchronized void shutdown() {
        lifecycleGeneration++;
        shuttingDown = true;
        enabled = false;
        try {
            if (subscription != null && subscription.isSubscribed()) {
                subscription.punsubscribe();
            }
        } catch (Throwable ignored) {
            // best effort
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        closeCommands();
    }

    private void closeCommands() {
        if (commands != null) {
            try {
                commands.close();
            } catch (Throwable t) {
                core.log(Level.WARNING, "Redis command client did not close cleanly: " + t.getMessage());
            } finally {
                commands = null;
            }
        }
        clientConfig = null;
    }

    private String channelKey(String channel) {
        return config.networkId + ":ch:" + channel;
    }

    private String cacheKey(String key) {
        return config.networkId + ":cache:" + key;
    }

    private void sleep() {
        try {
            Thread.sleep(5000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
