package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.perm.PermNodes;

import java.util.Locale;

/**
 * Centralized permission helper for chat channels.
 * Shared by:
 *  - ChatChannelService
 *  - RedisChatListener
 *  - Private Messages
 *  - Staff Spy
 *  - Cross-server features
 */
public final class ChatPermissions {

    private ChatPermissions() {}

    /**
     * @return true if the player can send messages into channelId.
     * Checks:
     *  - messentials.chat.channel.<id>.send
     *  - messentials.chat.channel.<id>.*
     *  - messentials.chat.channel.*
     */
    public static boolean canSend(CommonPlayer player, String channelId) {
        String base = PermNodes.chatChannelNode(channelId.toLowerCase(Locale.ROOT));
        return player.hasPermission(base + ".send")
                || player.hasPermission(base + ".*")
                || player.hasPermission(PermNodes.CHAT_CHANNEL_ALL)
                || player.hasPermission(PermNodes.MSG_SEND); // optional: allow MSG senders to talk anywhere
    }

    /**
     * @return true if the player can read messages from channelId.
     */
    public static boolean canRead(CommonPlayer player, String channelId) {
        String base = PermNodes.chatChannelNode(channelId.toLowerCase(Locale.ROOT));
        return player.hasPermission(base + ".read")
                || player.hasPermission(base + ".*")
                || player.hasPermission(PermNodes.CHAT_CHANNEL_ALL)
                || player.hasPermission(PermNodes.MSG_RECEIVE); // optional: reading permission
    }

    /**
     * @return true if the player can spy on private messages.
     * messentials.msg.spy
     */
    public static boolean canSpy(CommonPlayer player) {
        return player.hasPermission(PermNodes.MSG_SPY);
    }
}
