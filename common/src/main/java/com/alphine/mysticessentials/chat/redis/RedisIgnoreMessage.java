package com.alphine.mysticessentials.chat.redis;

import com.alphine.mysticessentials.config.MEConfig;

import java.util.UUID;

/**
 * Serializable representation of an ignore toggle over Redis.
 */
public class RedisIgnoreMessage {

    public String clusterId;
    public String originServerId;

    public String ownerUuid;  // the player who ignores/unignores
    public String targetUuid; // the player being ignored/unignored

    public boolean ignored;   // true = now ignored, false = now unignored

    public long timestamp;

    public RedisIgnoreMessage() {}

    public static RedisIgnoreMessage of(UUID owner, UUID target, boolean ignored) {
        MEConfig cfg = MEConfig.INSTANCE;
        MEConfig.Redis r = (cfg != null && cfg.chat != null) ? cfg.chat.redis : null;

        RedisIgnoreMessage msg = new RedisIgnoreMessage();
        msg.clusterId = (r != null && r.clusterId != null) ? r.clusterId : "default";
        msg.originServerId = (r != null && r.serverId != null) ? r.serverId : "server-unknown";

        msg.ownerUuid = owner != null ? owner.toString() : null;
        msg.targetUuid = target != null ? target.toString() : null;
        msg.ignored = ignored;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }
}
