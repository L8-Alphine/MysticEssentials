package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig.Channel;

/**
 * Abstraction over how a formatted chat message is delivered.
 *
 * For now we have a local-only implementation (sends to players on this JVM).
 * Later you can add a Redis-backed implementation for cross-server chat.
 */
public interface ChatDelivery {

    /**
     * Deliver a fully formatted MiniMessage string for a given channel + context.
     *
     * @param ctx   Chat context (server, sender, messageType, channelId, etc.)
     * @param channel Channel config (range, display settings, later: scope)
     * @param formattedMiniMessage Fully formatted MiniMessage string, ready to send
     */
    void deliver(ChatContext ctx, Channel channel, String formattedMiniMessage);
}
