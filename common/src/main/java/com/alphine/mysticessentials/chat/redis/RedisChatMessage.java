package com.alphine.mysticessentials.chat.redis;

import com.alphine.mysticessentials.chat.ChatContext;
import com.alphine.mysticessentials.config.MEConfig;

/**
 * Serializable representation of a chat message sent over Redis.
 *
 * This is intentionally minimal: it contains enough info for the receiver to:
 * - know which channel to deliver to
 * - re-create a lightweight ChatContext
 * - use the ready-to-send MiniMessage string
 */
public class RedisChatMessage {

    // Cluster / server context
    public String clusterId;
    public String serverId;

    // Core chat metadata
    public String channelId;
    public String messageType; // "minecraft:chat", "styled_chat:generic_hack", etc.

    // Sender identity (remote)
    public String senderUuid;
    public String senderName;
    public String senderWorldId; // optional, can be null for cross-server/global

    // Already formatted MiniMessage string
    public String formattedMiniMessage;

    // When it was originally sent (epoch millis)
    public long timestamp;

    public RedisChatMessage() {
    }

    /**
     * Build an envelope from a local ChatContext + formatted MiniMessage.
     */
    public static RedisChatMessage fromContext(ChatContext ctx, String formattedMiniMessage) {
        MEConfig cfg = MEConfig.INSTANCE;
        MEConfig.Chat chatCfg = cfg != null ? cfg.chat : null;
        MEConfig.Redis redisCfg = (chatCfg != null ? chatCfg.redis : null);

        RedisChatMessage msg = new RedisChatMessage();
        msg.clusterId = (redisCfg != null ? redisCfg.clusterId : "default");
        msg.serverId = (redisCfg != null ? redisCfg.serverId : "default");

        msg.channelId = ctx.channelId;
        msg.messageType = ctx.messageType;

        msg.senderUuid = ctx.sender.getUuid().toString();
        msg.senderName = ctx.sender.getName();
        msg.senderWorldId = ctx.sender.getWorldId();

        msg.formattedMiniMessage = formattedMiniMessage;
        msg.timestamp = ctx.timestamp.toEpochMilli();
        return msg;
    }
}
