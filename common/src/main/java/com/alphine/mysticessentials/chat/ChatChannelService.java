package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig;
import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig.Channel;
import com.alphine.mysticessentials.perm.PermNodes;

import java.util.Locale;
import java.util.Map;

public class ChatChannelService {

    /**
     * Resolve which channel this context should use for sending.
     * - If ctx.channelId is null → defaultChannel
     * - If unknown → defaultChannel
     * - If sender lacks ".send" → fall back to defaultChannel
     */
    public Channel resolveChannel(ChatContext ctx) {
        ChannelsConfig cfg = ChatConfigManager.CHANNELS;
        if (cfg == null) {
            Channel fallback = ChannelsConfig.Channel.createGlobalDefaults("global");
            ctx.channelId = "global";
            return fallback;
        }

        String requested = ctx.channelId;
        if (requested == null || requested.isBlank()) {
            requested = cfg.settings.defaultChannel;
        }

        Channel channel = findByIdOrAlias(cfg, requested);
        if (channel == null) {
            channel = findByIdOrAlias(cfg, cfg.settings.defaultChannel);
            if (channel == null) {
                channel = ChannelsConfig.Channel.createGlobalDefaults("global");
            }
        }

        // normalize ctx.channelId to the canonical id
        ctx.channelId = channel.id != null ? channel.id : requested.toLowerCase(Locale.ROOT);

        // Check ".send" permission for this channel
        if (!canSend(ctx.sender, ctx.channelId)) {
            // fallback to default channel
            String defId = cfg.settings.defaultChannel;
            Channel def = findByIdOrAlias(cfg, defId);
            if (def == null) {
                def = ChannelsConfig.Channel.createGlobalDefaults("global");
                ctx.channelId = "global";
                return def;
            }
            ctx.channelId = def.id != null ? def.id : defId.toLowerCase(Locale.ROOT);
            return def;
        }

        return channel;
    }

    /**
     * Resolve a channel from a user-facing name (id or alias).
     * Useful for commands like /global, /staff, /sc, etc.
     */
    public Channel resolveByUserInput(String input) {
        ChannelsConfig cfg = ChatConfigManager.CHANNELS;
        if (cfg == null || input == null || input.isBlank()) return null;
        return findByIdOrAlias(cfg, input);
    }

    // ------------------------------------------------------------------------
    // Permission helpers
    // ------------------------------------------------------------------------

    /**
     * Whether the player can send messages into the given channel id.
     * Uses messentials.chat.channel.<id>.send and wildcards.
     */
    public boolean canSend(CommonPlayer player, String channelId) {
        String base = permissionBaseFor(channelId);
        return player.hasPermission(base + ".send")
                || player.hasPermission(base + ".*")
                || player.hasPermission(PermNodes.CHAT_CHANNEL_ALL); // messentials.chat.channel.*
    }

    /**
     * Whether the player can receive messages from the given channel id.
     * Uses messentials.chat.channel.<id>.read and wildcards.
     */
    public boolean canRead(CommonPlayer player, String channelId) {
        String base = permissionBaseFor(channelId);
        return player.hasPermission(base + ".read")
                || player.hasPermission(base + ".*")
                || player.hasPermission(PermNodes.CHAT_CHANNEL_ALL);
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    private Channel findByIdOrAlias(ChannelsConfig cfg, String input) {
        String key = input.toLowerCase(Locale.ROOT);
        Map<String, Channel> map = cfg.channels;

        // 1) exact id match
        Channel byId = map.get(key);
        if (byId != null) return byId;

        // 2) alias match
        for (Channel ch : map.values()) {
            if (ch.aliases == null) continue;
            for (String alias : ch.aliases) {
                if (alias.equalsIgnoreCase(key)) {
                    return ch;
                }
            }
        }

        return null;
    }

    /**
     * Determine the permission base for a channel id:
     * - If channel.permissionBase is set, use that.
     * - Otherwise fall back to PermNodes.chatChannelNode(id).
     */
    private String permissionBaseFor(String channelId) {
        ChannelsConfig cfg = ChatConfigManager.CHANNELS;
        if (cfg != null && cfg.channels != null && channelId != null) {
            Channel c = cfg.channels.get(channelId.toLowerCase(Locale.ROOT));
            if (c != null && c.permissionBase != null && !c.permissionBase.isBlank()) {
                return c.permissionBase;
            }
        }
        // default: "messentials.chat.channel.<id>"
        return PermNodes.chatChannelNode(channelId);
    }
}
