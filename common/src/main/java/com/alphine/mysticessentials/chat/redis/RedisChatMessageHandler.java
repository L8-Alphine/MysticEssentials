package com.alphine.mysticessentials.chat.redis;

import com.alphine.mysticessentials.chat.ChatChannelService;
import com.alphine.mysticessentials.chat.ChatPermissions;
import com.alphine.mysticessentials.chat.LocalChatDelivery;
import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.chat.platform.CommonServer;
import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig.Channel;
import com.alphine.mysticessentials.config.MEConfig;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class RedisChatMessageHandler {

    private final CommonServer server;
    private final ChatChannelService channels;
    private final LocalChatDelivery localDelivery;
    private final Gson gson = new Gson();

    public RedisChatMessageHandler(CommonServer server,
                                   ChatChannelService channels,
                                   LocalChatDelivery localDelivery) {
        this.server = server;
        this.channels = channels;
        this.localDelivery = localDelivery;
    }

    public void handleIncoming(String topic, String json) {

        RedisChatMessage msg;

        try {
            msg = gson.fromJson(json, RedisChatMessage.class);
        } catch (JsonSyntaxException ex) {
            server.logToConsole("[RedisChat] Invalid JSON on " + topic + ": " + ex.getMessage());
            return;
        }

        if (msg == null || msg.channelId == null || msg.formattedMiniMessage == null) {
            server.logToConsole("[RedisChat] Dropping malformed inbound message.");
            return;
        }

        MEConfig cfg = MEConfig.INSTANCE;
        if (cfg == null || cfg.chat == null || cfg.chat.redis == null) {
            server.logToConsole("[RedisChat] Redis not fully configured.");
            return;
        }

        // Ignore same server
        if (cfg.chat.redis.serverId != null &&
                cfg.chat.redis.serverId.equals(msg.serverId)) {
            return;
        }

        // Load channels
        var chCfg = ChatConfigManager.CHANNELS;
        if (chCfg == null || chCfg.channels == null) {
            server.logToConsole("[RedisChat] No channels loaded.");
            return;
        }

        Channel channel = chCfg.channels.get(msg.channelId.toLowerCase());
        if (channel == null) {
            server.logToConsole("[RedisChat] Unknown channel '" + msg.channelId + "'");
            return;
        }

        // Deliver to all online players who have permission to read
        for (CommonPlayer p : server.getOnlinePlayers()) {
            if (!ChatPermissions.canRead(p, msg.channelId)) continue;
            p.sendChatMessage(msg.formattedMiniMessage);
        }
    }

}
