package org.hyzionstudios.mysticessentials.api.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.api.chat.ChatDeliveryResult;
import org.hyzionstudios.mysticessentials.api.chat.ChatMute;
import org.hyzionstudios.mysticessentials.api.mention.MentionScopeProvider;

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

    /**
     * Creates an in-memory temporary channel owned by a player.
     *
     * @return {@code false} when the channel cannot be created, including when the
     *         owner already owns {@code channels.maxTemporaryChannelsPerOwner} of them
     */
    boolean createTemporaryChannel(UUID owner, String channelId, String permissionGate);

    /** The ids of temporary channels currently active on this server. */
    default Set<String> temporaryChannelIds() {
        return Set.of();
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

    // ----- Mention scopes --------------------------------------------------------

    /**
     * Adds an option to every player's "who may mention you" list.
     *
     * <p>Use this for any scope that depends on a relationship this mod does not
     * model — friends, guild members, party members. Mystic Essentials ships only
     * {@code everyone} and {@code nobody}, because those are the only two it can
     * enforce by itself; a scope with no provider is hidden rather than offered as
     * a setting that would quietly do nothing.</p>
     *
     * <p>Re-registering the same id replaces the previous provider, so a mod
     * reload does not accumulate duplicates.</p>
     */
    default void registerMentionScope(MentionScopeProvider provider) {
    }

    /** Removes a scope by id. @return whether one was registered. */
    default boolean unregisterMentionScope(String scopeId) {
        return false;
    }

    /**
     * The currently available scopes, in display order. Excludes providers whose
     * {@code isAvailable()} is false.
     */
    default List<MentionScopeProvider> mentionScopes() {
        return List.of();
    }

    // ----- Mutes, ignores and delivery for other mods --------------------------

    /**
     * The server chat mute in force for a player, if any. Mystic Essentials keeps no
     * server mutes of its own, so this is MysticModeration's active mute; it is empty
     * when MysticModeration is absent, its integration is disabled, or it fails.
     *
     * <p>Includes shadow mutes: check {@link ChatMute#shadow()} before telling the
     * player anything.</p>
     */
    default Optional<ChatMute> activeMute(UUID player) {
        return Optional.empty();
    }

    /**
     * Like {@link #activeMute(UUID)}, falling back to the channel moderation mute the
     * player holds in the Mystic Essentials channel {@code channelId} (temporary
     * channels). A server mute wins over a channel mute.
     */
    default Optional<ChatMute> activeMute(UUID player, String channelId) {
        return activeMute(player);
    }

    /** Whether {@link #activeMute(UUID)} holds a mute, shadow mutes included. */
    default boolean isMuted(UUID player) {
        return activeMute(player).isPresent();
    }

    /**
     * Whether {@code recipient} ignores {@code sender}: the sender is on the blocked
     * players list of the recipient's {@code /mentions} settings. Always {@code false}
     * for the same player.
     */
    default boolean isIgnoring(UUID recipient, UUID sender) {
        return false;
    }

    /**
     * Delivers one line a player wrote in a chat context another mod owns (guild,
     * officer, party or settlement chat) to players on this server, under Mystic
     * Essentials' chat rules. Same as {@code deliver(sender, recipients, channelLabel,
     * message, null)}.
     *
     * @see #deliver(UUID, Collection, String, String, String)
     */
    default ChatDeliveryResult deliver(UUID sender, Collection<UUID> recipients, String channelLabel,
            String message) {
        return deliver(sender, recipients, channelLabel, message, null);
    }

    /**
     * Delivers one line a player wrote in a chat context another mod owns (guild,
     * officer, party or settlement chat) to players on this server, under Mystic
     * Essentials' chat rules:
     *
     * <ul>
     *   <li>the sender must be online on this server;</li>
     *   <li>a muted sender ({@link #activeMute(UUID)}) is refused with
     *       {@link ChatDeliveryResult.Status#MUTED} and told why; a shadow-muted one sees
     *       the line alone ({@link ChatDeliveryResult.Status#SHADOW_MUTED});</li>
     *   <li>the message is cleaned as public chat is: colour styles the sender lacks the
     *       permission for are stripped, it is cut to the chat length limit, and it is
     *       never parsed for placeholders;</li>
     *   <li>a recipient who is not online here, who ignores the sender
     *       ({@link #isIgnoring(UUID, UUID)}), or whom a managed account's policy keeps
     *       apart from the sender (MysticIdentity) is skipped;</li>
     *   <li>the sender always gets their own line, whether or not they are in
     *       {@code recipients};</li>
     *   <li>the line is echoed to the server log, like public chat.</li>
     * </ul>
     *
     * <p>Nothing is relayed to other servers and no
     * {@link org.hyzionstudios.mysticessentials.api.event.ChatMessagePublishedEvent} is
     * fired. Safe to call from any thread.</p>
     *
     * @param recipients   who should get the line; {@code null} entries and duplicates
     *                     are ignored
     * @param channelLabel the label shown as {@code {channel}}, e.g. a guild name;
     *                     colour codes are kept, other markup is removed
     * @param message      the text the player typed
     * @param format       the line format, or {@code null} for the chat module's
     *                     {@code deliveryFormat}. It supports colour codes, the sender's
     *                     placeholders, {@code {channel}}, {@code {player_name}},
     *                     {@code {display_name}} and {@code {message}}
     */
    default ChatDeliveryResult deliver(UUID sender, Collection<UUID> recipients, String channelLabel,
            String message, String format) {
        return new ChatDeliveryResult(ChatDeliveryResult.Status.UNAVAILABLE, 0, 0, "");
    }
}
