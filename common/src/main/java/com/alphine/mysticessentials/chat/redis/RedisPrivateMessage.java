package com.alphine.mysticessentials.chat.redis;

import com.alphine.mysticessentials.config.MEConfig;

import java.util.UUID;

/**
 * Serializable representation of a private message for Redis.
 */
public class RedisPrivateMessage {

    public String clusterId;
    public String originServerId;

    public String senderUuid;
    public String senderName;

    public String targetUuid;   // may be null if origin server had only name
    public String targetName;

    public String rawMessage;
    public long timestamp;

    public RedisPrivateMessage() {}

    public static RedisPrivateMessage fromLocal(UUID senderUuid,
                                                String senderName,
                                                UUID targetUuid,
                                                String targetName,
                                                String raw) {

        MEConfig cfg = MEConfig.INSTANCE;
        MEConfig.Redis r = (cfg != null && cfg.chat != null) ? cfg.chat.redis : null;

        RedisPrivateMessage msg = new RedisPrivateMessage();
        msg.clusterId = (r != null && r.clusterId != null) ? r.clusterId : "default";
        msg.originServerId = (r != null && r.serverId != null) ? r.serverId : "server-unknown";

        msg.senderUuid = senderUuid != null ? senderUuid.toString() : null;
        msg.senderName = senderName;

        msg.targetUuid = targetUuid != null ? targetUuid.toString() : null;
        msg.targetName = targetName;

        msg.rawMessage = raw;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }
}
