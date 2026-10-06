package org.hyzionstudios.mysticessentials.api.chat;

import java.time.Instant;

/**
 * A mute that keeps a player's chat from reaching other players.
 *
 * <p>Mystic Essentials keeps no server-wide mutes of its own: those belong to the
 * moderation plugin (MysticModeration), and Mystic Essentials reports the one it holds.
 * Its own mutes are channel moderation mutes in temporary channels.</p>
 *
 * @param scope     where the mute applies
 * @param reason    the moderator's reason; empty when none was given, never {@code null}
 * @param expiresAt when the mute ends, or {@code null} when it is permanent
 * @param shadow    a shadow mute: the player's lines are shown to them alone and they
 *                  are never told, so never reveal it to them
 */
public record ChatMute(Scope scope, String reason, Instant expiresAt, boolean shadow) {

    public ChatMute {
        reason = reason == null ? "" : reason;
    }

    /** Where a {@link ChatMute} applies. */
    public enum Scope {
        /** A server mute from the moderation plugin; it covers all player chat. */
        SERVER,
        /** A channel moderator's mute in one Mystic Essentials channel. */
        CHANNEL
    }
}
