package com.alphine.mysticessentials.chat.redis;

@FunctionalInterface
public interface RedisMessageHandler {
    void onMessage(String channel, String payload);
}
