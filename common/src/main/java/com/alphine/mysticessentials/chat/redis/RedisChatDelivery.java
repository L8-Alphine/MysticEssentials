package com.alphine.mysticessentials.chat.redis;

import com.alphine.mysticessentials.chat.ChatContext;
import com.alphine.mysticessentials.chat.ChatDelivery;
import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig.Channel;
import com.alphine.mysticessentials.config.MEConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Redis-backed delivery stub.
 *
 * Behavior:
 *  - If Redis is disabled (or adapter is null), falls back to localDelivery only.
 *  - If channel.scope == LOCAL, only localDelivery is used.
 *  - If channel.scope == SERVER or GLOBAL:
 *      - Logs the intended Redis topic
 *      - Publishes a JSON envelope to Redis (if adapter != null)
 *      - Also calls localDelivery so the originating server still sees the message immediately
 */
public class RedisChatDelivery implements ChatDelivery {

    private final ChatDelivery localDelivery;
    private final MEConfig.Chat chatCfg;
    private final RedisClientAdapter redis;

    private final Gson gson = new GsonBuilder().create();

    public RedisChatDelivery(ChatDelivery localDelivery, RedisClientAdapter redis) {
        this.localDelivery = localDelivery;
        this.redis = redis;
        this.chatCfg = MEConfig.INSTANCE != null ? MEConfig.INSTANCE.chat : null;
    }

    @Override
    public void deliver(ChatContext ctx, Channel channel, String formattedMiniMessage) {
        if (channel == null) return;

        if (chatCfg == null || chatCfg.redis == null || !chatCfg.redis.enabled) {
            localDelivery.deliver(ctx, channel, formattedMiniMessage);
            return;
        }

        Channel.Scope scope = channel.scope != null ? channel.scope : Channel.Scope.LOCAL;

        String topic = null;

        switch (scope) {
            case LOCAL -> {
                localDelivery.deliver(ctx, channel, formattedMiniMessage);
                return; // no redis publish
            }
            case SERVER -> {
                topic = buildServerTopic(chatCfg);
                ctx.server.logToConsole("[RedisChat] Publish SERVER → " + topic);
            }
            case GLOBAL -> {
                topic = buildGlobalTopic(chatCfg);
                ctx.server.logToConsole("[RedisChat] Publish GLOBAL → " + topic);
            }
        }

        // Build envelope
        RedisChatMessage envelope = RedisChatMessage.fromContext(ctx, formattedMiniMessage);
        String json = gson.toJson(envelope);

        // Publish
        redis.publish(topic, json);

        // Always deliver locally too
        localDelivery.deliver(ctx, channel, formattedMiniMessage);
    }

    private String buildGlobalTopic(MEConfig.Chat cfg) {
        MEConfig.Redis r = cfg.redis;
        // e.g. mystic:chat:global
        return r.clusterId + ":" + r.globalChannelBase;
    }

    private String buildServerTopic(MEConfig.Chat cfg) {
        MEConfig.Redis r = cfg.redis;
        // e.g. mystic:chat:server:avalon-1
        return r.clusterId + ":" + r.serverChannelBase + ":" + r.serverId;
    }
}
