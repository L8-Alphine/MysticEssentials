package org.hyzionstudios.mysticessentials.api.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Full chat framework: rank/permission formatting, colors, private messages,
 * and channels (including optional Redis-backed cross-server channels).
 */
public interface ChatService {

    /** Resolves the chat format string that applies to a player (highest-priority match). */
    String resolveFormat(UUID player);

    /** Sends a private message. @return {@code true} if delivered; {@code false} if the target is offline/absent. */
    CompletableFuture<Boolean> privateMessage(UUID from, UUID to, String message);

    /** The channel the player is currently talking in (e.g. {@code "global"}). */
    String currentChannel(UUID player);

    /** Moves the player into a channel. @return {@code true} on success. */
    boolean setChannel(UUID player, String channelId);

    /** Creates an in-memory temporary channel owned by a player. */
    boolean createTemporaryChannel(UUID owner, String channelId, String permissionGate);

    /** The ids of temporary channels currently active on this server. */
    default java.util.Set<String> temporaryChannelIds() {
        return java.util.Set.of();
    }

    /**
     * Delivers an externally sourced message (e.g. a bridged Discord message) to this
     * server's channel listeners only — callers that span servers are expected to invoke
     * this on every server themselves. The sender is a plain display name, not a player;
     * the message does not fire
     * {@link org.hyzionstudios.mysticessentials.api.event.ChatMessagePublishedEvent}.
     *
     * @return {@code true} if the channel exists and is enabled.
     */
    boolean broadcastToChannel(String channelId, String senderName, String content);

    /**
     * Like {@link #broadcastToChannel(String, String, String)} but rendered with the
     * caller-supplied format line instead of the channel's own format. The format supports
     * color codes and the placeholders {@code {player_name}}, {@code {display_name}},
     * {@code {channel}}, {@code {server_id}}, and {@code {message}}.
     */
    default boolean broadcastToChannel(String channelId, String senderName, String content, String format) {
        return broadcastToChannel(channelId, senderName, content);
    }
}
