package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig.Channel;

/**
 * Simple composite that forwards to multiple ChatDelivery implementations.
 */
public class CompositeChatDelivery implements ChatDelivery {

    private final ChatDelivery[] delegates;

    public CompositeChatDelivery(ChatDelivery... delegates) {
        this.delegates = delegates;
    }

    @Override
    public void deliver(ChatContext ctx, Channel channel, String formattedMiniMessage) {
        for (ChatDelivery d : delegates) {
            if (d != null) {
                d.deliver(ctx, channel, formattedMiniMessage);
            }
        }
    }
}
