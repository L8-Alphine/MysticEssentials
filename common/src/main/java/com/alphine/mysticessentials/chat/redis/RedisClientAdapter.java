package com.alphine.mysticessentials.chat.redis;

public interface RedisClientAdapter {

    /** Publish a message to a channel. */
    void publish(String channel, String payload);

    /**
     * Subscribe to a Redis pub/sub channel.
     * Implementations should run the handler on the *MC main thread* (via server.execute()).
     */
    void subscribe(String channel, RedisMessageHandler handler);

    /** Shut down connections / threads. */
    void shutdown();
}
