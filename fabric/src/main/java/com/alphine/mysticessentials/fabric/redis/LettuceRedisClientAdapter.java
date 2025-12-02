package com.alphine.mysticessentials.fabric.redis;

import com.alphine.mysticessentials.chat.redis.RedisClientAdapter;
import com.alphine.mysticessentials.chat.redis.RedisMessageHandler;
import com.alphine.mysticessentials.config.MEConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LettuceRedisClientAdapter implements RedisClientAdapter {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> conn;
    private final RedisCommands<String, String> commands;

    // Pub/Sub
    private StatefulRedisPubSubConnection<String, String> subConn;
    private final Map<String, RedisMessageHandler> handlers = new ConcurrentHashMap<>();

    public LettuceRedisClientAdapter(MEConfig.Redis cfg) {
        String uri = buildUri(cfg); // redis://[user:pass@]host:port[/db]
        this.client = RedisClient.create(uri);
        this.conn = client.connect();
        this.commands = conn.sync();
    }

    private String buildUri(MEConfig.Redis cfg) {
        String authPart = "";
        if (cfg.password != null && !cfg.password.isBlank()) {
            if (cfg.username != null && !cfg.username.isBlank()) {
                authPart = cfg.username + ":" + cfg.password + "@";
            } else {
                authPart = ":" + cfg.password + "@";
            }
        }
        String scheme = cfg.useSsl ? "rediss" : "redis";
        return scheme + "://" + authPart + cfg.host + ":" + cfg.port;
    }

    // ------------------------------------------------------------------------
    // Publish
    // ------------------------------------------------------------------------
    @Override
    public void publish(String topic, String json) {
        try {
            commands.publish(topic, json);
        } catch (Exception ex) {
            System.err.println("[MysticEssentials][Redis] Failed to publish to "
                    + topic + ": " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // Subscribe (channel or pattern)
    // ------------------------------------------------------------------------
    @Override
    public void subscribe(String channelOrPattern, RedisMessageHandler handler) {
        if (handler == null || channelOrPattern == null || channelOrPattern.isBlank()) {
            return;
        }

        ensureSubscriber();

        // store handler keyed by the exact channel/pattern we subscribed with
        handlers.put(channelOrPattern, handler);

        try {
            if (channelOrPattern.contains("*")) {
                // pattern subscription
                subConn.sync().psubscribe(channelOrPattern);
            } else {
                // direct channel subscription
                subConn.sync().subscribe(channelOrPattern);
            }
        } catch (Exception ex) {
            System.err.println("[MysticEssentials][Redis] Failed to subscribe to "
                    + channelOrPattern + ": " + ex.getMessage());
        }
    }

    private void ensureSubscriber() {
        if (subConn != null) return;

        subConn = client.connectPubSub();
        subConn.addListener(new RedisPubSubListener<>() {
            @Override
            public void message(String channel, String message) {
                // direct channel subscription
                RedisMessageHandler h = handlers.get(channel);
                if (h != null) {
                    try {
                        h.onMessage(channel, message);
                    } catch (Exception ex) {
                        System.err.println("[MysticEssentials][Redis] Error in handler for channel "
                                + channel + ": " + ex.getMessage());
                    }
                }
            }

            @Override
            public void message(String pattern, String channel, String message) {
                // pattern-based subscription
                RedisMessageHandler h = handlers.get(pattern);
                if (h != null) {
                    try {
                        h.onMessage(channel, message); // pass actual channel
                    } catch (Exception ex) {
                        System.err.println("[MysticEssentials][Redis] Error in pattern handler "
                                + pattern + " for channel " + channel + ": " + ex.getMessage());
                    }
                }
            }

            @Override public void subscribed(String channel, long count) {}
            @Override public void psubscribed(String pattern, long count) {}
            @Override public void unsubscribed(String channel, long count) {}
            @Override public void punsubscribed(String pattern, long count) {}
        });
    }

    // ------------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------------
    @Override
    public void shutdown() {
        try {
            if (subConn != null) {
                subConn.close();
                subConn = null;
            }
        } catch (Exception ignored) {}

        try {
            conn.close();
        } catch (Exception ignored) {}

        try {
            client.shutdown();
        } catch (Exception ignored) {}
    }
}
