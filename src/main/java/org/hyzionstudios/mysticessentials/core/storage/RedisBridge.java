package org.hyzionstudios.mysticessentials.core.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.config.MainConfig;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonObject;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

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
    private JedisPool pool;
    private Thread subscriberThread;
    private volatile JedisPubSub subscription;
    private volatile boolean shuttingDown;

    public RedisBridge(MysticCore core) {
        this.core = core;
    }

    public void init(MainConfig.Redis config) {
        this.config = config;
        if (config == null || !config.enabled) {
            core.log(Level.INFO, "Redis disabled; cross-server features run in local-only mode.");
            return;
        }
        try {
            String password = config.password == null || config.password.isBlank() ? null : config.password;
            pool = new JedisPool(new JedisPoolConfig(), config.host, config.port, 2000, password);
            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
            }
            enabled = true;
            startSubscriber();
            core.log(Level.INFO, "Redis connected at " + config.host + ":" + config.port
                    + " (network=" + config.networkId + ", server=" + config.serverId + ")");
        } catch (Throwable t) {
            core.log(Level.SEVERE, "Redis enabled but connection failed (" + t.getMessage()
                    + "); running in local-only mode.");
            enabled = false;
            closePool();
        }
    }

    public boolean isEnabled() {
        return enabled;
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

    /** Publishes a payload to a logical channel across the network. No-op if Redis is unavailable. */
    public void publish(String channel, String payload) {
        if (!enabled) {
            return;
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("s", config.serverId);
        envelope.addProperty("c", channel);
        envelope.addProperty("m", payload);
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channelKey(channel), Json.toString(envelope));
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
        subscriberThread = new Thread(this::subscriberLoop, "MysticEssentials-Redis-Sub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    private void subscriberLoop() {
        String pattern = channelKey("*");
        while (enabled && !shuttingDown) {
            try (Jedis jedis = pool.getResource()) {
                jedis.psubscribe(subscription, pattern); // blocks until punsubscribe
            } catch (Throwable t) {
                if (shuttingDown) {
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
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(cacheKey(key));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Writes a cached value with a time-to-live in seconds. No-op if unavailable. */
    public void cacheSet(String key, String value, long ttlSeconds) {
        if (!enabled) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            if (ttlSeconds > 0) {
                jedis.setex(cacheKey(key), ttlSeconds, value);
            } else {
                jedis.set(cacheKey(key), value);
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
        try (Jedis jedis = pool.getResource()) {
            String[] namespaced = new String[keys.length];
            for (int i = 0; i < keys.length; i++) {
                namespaced[i] = cacheKey(keys[i]);
            }
            jedis.del(namespaced);
        } catch (Throwable t) {
            core.log(Level.WARNING, "Redis cacheDelete failed: " + t);
        }
    }

    // ----- Lifecycle / helpers ----------------------------------------------

    public void shutdown() {
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
        closePool();
    }

    private void closePool() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
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
