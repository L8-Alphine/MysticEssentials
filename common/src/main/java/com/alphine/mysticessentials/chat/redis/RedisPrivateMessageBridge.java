package com.alphine.mysticessentials.chat.redis;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.config.MEConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.UUID;

/**
 * Handles inbound Redis PM / ignore messages.
 * You should hook this into your RedisClientAdapter subscriber.
 */
public class RedisPrivateMessageBridge {

    private final Gson gson = new GsonBuilder().create();
    private final MinecraftServer server;

    public RedisPrivateMessageBridge(MinecraftServer server) {
        this.server = server;
    }

    public void handleIncomingPm(String topic, String json) {
        RedisPrivateMessage msg;
        try {
            msg = gson.fromJson(json, RedisPrivateMessage.class);
        } catch (JsonSyntaxException ex) {
            server.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[MysticEssentials][RedisPM] Invalid JSON on " + topic + ": " + ex.getMessage()
            ));
            return;
        }

        if (msg == null || msg.senderName == null || msg.targetName == null || msg.rawMessage == null) {
            return;
        }

        MEConfig cfg = MEConfig.INSTANCE;
        if (cfg == null || cfg.chat == null || cfg.chat.redis == null) {
            return;
        }

        // Ignore messages from this same server
        if (cfg.chat.redis.serverId != null
                && cfg.chat.redis.serverId.equalsIgnoreCase(msg.originServerId)) {
            return;
        }

        // Find target on THIS server (name or UUID)
        ServerPlayer target = null;
        if (msg.targetUuid != null) {
            try {
                UUID uuid = UUID.fromString(msg.targetUuid);
                target = server.getPlayerList().getPlayer(uuid);
            } catch (IllegalArgumentException ignored) {}
        }
        if (target == null) {
            // Fallback: lookup by name (case-insensitive)
            String nameLower = msg.targetName.toLowerCase(Locale.ROOT);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.getGameProfile().getName().toLowerCase(Locale.ROOT).equals(nameLower)) {
                    target = p;
                    break;
                }
            }
        }

        if (target == null) {
            return; // target not on this server
        }

        UUID senderUuid = null;
        if (msg.senderUuid != null) {
            try {
                senderUuid = UUID.fromString(msg.senderUuid);
            } catch (IllegalArgumentException ignored) {}
        }

        // Deliver via PrivateMessageService (remote variant)
        MysticEssentialsCommon.get().privateMessages.deliverRemote(
                senderUuid,
                msg.senderName,
                target,
                msg.rawMessage
        );
    }

    public void handleIncomingIgnore(String topic, String json) {
        RedisIgnoreMessage msg;
        try {
            msg = gson.fromJson(json, RedisIgnoreMessage.class);
        } catch (JsonSyntaxException ex) {
            server.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[MysticEssentials][RedisPM] Invalid IGNORE JSON on " + topic + ": " + ex.getMessage()
            ));
            return;
        }

        if (msg == null || msg.ownerUuid == null || msg.targetUuid == null) {
            return;
        }

        MEConfig cfg = MEConfig.INSTANCE;
        if (cfg == null || cfg.chat == null || cfg.chat.redis == null) {
            return;
        }

        // Ignore local echo
        if (cfg.chat.redis.serverId != null
                && cfg.chat.redis.serverId.equalsIgnoreCase(msg.originServerId)) {
            return;
        }

        try {
            UUID owner = UUID.fromString(msg.ownerUuid);
            UUID target = UUID.fromString(msg.targetUuid);
            MysticEssentialsCommon.get().privateMessages.applyIgnoreFromRemote(owner, target, msg.ignored);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
