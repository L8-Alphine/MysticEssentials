package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig.Channel;

/**
 * Local-only delivery: sends messages to players connected to this server instance.
 *
 * - Respects channel READ permissions via ChatChannelService.canRead(...)
 * - Respects channel range (for "local" style channels)
 */
public class LocalChatDelivery implements ChatDelivery {

    private final ChatChannelService channelService;

    public LocalChatDelivery(ChatChannelService channelService) {
        this.channelService = channelService;
    }

    @Override
    public void deliver(ChatContext ctx, Channel channel, String formattedMiniMessage) {
        if (channel == null) return;

        for (CommonPlayer target : ctx.server.getOnlinePlayers()) {
            // 1) Permission check: can this player read this channel?
            if (!channelService.canRead(target, ctx.channelId)) continue;

            // 2) Range check for local channels
            if (!isInRange(channel, ctx.sender, target)) continue;

            // 3) Send MiniMessage-formatted text
            target.sendChatMessage(formattedMiniMessage);
        }
    }

    private boolean isInRange(Channel channel, CommonPlayer sender, CommonPlayer target) {
        if (channel.range <= 0) return true; // 0 or <0 => unlimited (global)

        // Only within same world
        if (!sender.getWorldId().equals(target.getWorldId())) return false;

        double dx = sender.getX() - target.getX();
        double dy = sender.getY() - target.getY();
        double dz = sender.getZ() - target.getZ();
        return (dx * dx + dy * dy + dz * dz) <= (channel.range * (double) channel.range);
    }
}
