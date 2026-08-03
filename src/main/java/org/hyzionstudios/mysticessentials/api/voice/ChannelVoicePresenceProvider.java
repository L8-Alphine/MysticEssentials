package org.hyzionstudios.mysticessentials.api.voice;

import java.util.UUID;

/**
 * Adapter that reports live voice presence for channel members (design bible §11.4).
 * MysticEssentials never depends on a single voice implementation; instead a voice
 * mod (or the MysticIdentity Discord bridge) registers a provider and the roster
 * queries it.
 *
 * <p>Critically, without a registered provider the UI must <strong>not</strong>
 * claim a player is actively speaking — the {@link #NONE no-op provider} answers
 * {@code false}/{@code false} to every query so text-only channels fall back to
 * typing / recent-activity indicators instead of a fabricated speaking state.</p>
 *
 * <p>Implementations are queried from the (async) roster-build path and must be
 * cheap and non-blocking: return cached state, never perform I/O.</p>
 */
public interface ChannelVoicePresenceProvider {

    /** @return {@code true} if the player is connected to the channel's voice room. */
    boolean isConnected(UUID playerId, String channelId);

    /** @return {@code true} if the player is currently transmitting audio. */
    boolean isSpeaking(UUID playerId, String channelId);

    /** @return {@code true} if the player has muted themselves locally. */
    boolean isLocallyMuted(UUID playerId, String channelId);

    /** @return {@code true} if the player has been muted by the server/voice moderation. */
    boolean isServerMuted(UUID playerId, String channelId);

    /**
     * The default provider used when no voice integration is present. Reports every
     * player as not-connected and not-speaking, so the roster never fabricates a
     * live speaking indicator (§11.4).
     */
    ChannelVoicePresenceProvider NONE = new ChannelVoicePresenceProvider() {
        @Override
        public boolean isConnected(UUID playerId, String channelId) {
            return false;
        }

        @Override
        public boolean isSpeaking(UUID playerId, String channelId) {
            return false;
        }

        @Override
        public boolean isLocallyMuted(UUID playerId, String channelId) {
            return false;
        }

        @Override
        public boolean isServerMuted(UUID playerId, String channelId) {
            return false;
        }
    };
}
