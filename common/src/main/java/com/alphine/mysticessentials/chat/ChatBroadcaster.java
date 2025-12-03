package com.alphine.mysticessentials.chat;

@FunctionalInterface
public interface ChatBroadcaster {

    /**
     * Broadcast a MiniMessage-formatted string to all players.
     * Implement this with your existing chat pipeline
     * (MiniMessage -> Component -> send to online players).
     */
    void broadcast(String miniMessage);
}
