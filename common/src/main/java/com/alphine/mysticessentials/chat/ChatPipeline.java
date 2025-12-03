package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.chat.redis.RedisChatDelivery;
import com.alphine.mysticessentials.chat.redis.RedisClientAdapter;
import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig.Channel;
import com.alphine.mysticessentials.config.MEConfig;

public class ChatPipeline {

    private final ChatReplacementsService replacements = new ChatReplacementsService();
    private final ChatTriggersService triggers = new ChatTriggersService();
    private final ChatTagsService tags = new ChatTagsService();
    private final ChatMentionService mentions = new ChatMentionService();
    private final ChatColorService colors = new ChatColorService();
    private final ChatChannelService channels = new ChatChannelService();

    private final ChatDelivery delivery;
    private final ChatFormatter formatter;

    private final ChatHistoryService history = new ChatHistoryService();

    public ChatPipeline(RedisClientAdapter redisAdapter) {
        this.delivery = createDelivery(redisAdapter);
        this.formatter = new ChatFormatter(this.delivery);
    }

    private ChatDelivery createDelivery(RedisClientAdapter redisAdapter) {
        ChatDelivery local = new LocalChatDelivery(channels);

        MEConfig cfg = MEConfig.INSTANCE;
        boolean redisEnabled =
                cfg != null &&
                        cfg.chat != null &&
                        cfg.chat.redis != null &&
                        cfg.chat.redis.enabled &&
                        redisAdapter != null;

        if (!redisEnabled) {
            return local;
        }

        return new RedisChatDelivery(local, redisAdapter);
    }

    public boolean handle(ChatContext ctx) {
        replacements.apply(ctx);
        if (triggers.handle(ctx)) return true;

        tags.process(ctx);
        mentions.process(ctx);
        colors.apply(ctx);

        Channel channel = channels.resolveChannel(ctx);
        formatter.sendChannelMessage(ctx, channel);

        history.record(ctx);
        return true;
    }
}
