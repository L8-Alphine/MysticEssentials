package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.chat.platform.CommonServer;
import com.alphine.mysticessentials.chat.redis.RedisClientAdapter;
import com.alphine.mysticessentials.config.MEConfig;

public final class ChatModule {

    private static ChatPipeline PIPELINE;

    public static void init(RedisClientAdapter redis) {
        PIPELINE = new ChatPipeline(redis);
    }

    private ChatModule() {}

    /**
     * @return true if MysticEssentials fully handled this chat message and
     *         the platform should cancel the default / vanilla handling.
     */
    public static boolean handleChat(CommonServer server,
                                     CommonPlayer sender,
                                     String rawMessage,
                                     String messageType) {

        MEConfig cfg = MEConfig.INSTANCE;
        if (cfg == null ||
                !cfg.features.enableChatSystem ||
                cfg.chat == null ||
                !cfg.chat.enabled) {
            // Chat system disabled -> we did nothing, let vanilla continue
            return false;
        }

        if (PIPELINE == null) {
            // safety: no Redis yet
            PIPELINE = new ChatPipeline(null);
        }

        ChatContext ctx = new ChatContext(server, sender, rawMessage, messageType);

        // pull the player's active channel
        ctx.channelId = MysticEssentialsCommon.get()
                .chatState
                .getActiveChannel(sender.getUuid());

        return PIPELINE.handle(ctx);
    }
}
