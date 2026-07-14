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
}
