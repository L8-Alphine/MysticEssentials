package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores per-player active channel selection.
 * Used by /channel and the chat pipeline.
 */
public class ChatStateService {

    private final Map<UUID, String> activeChannel = new ConcurrentHashMap<>();

    /**
     * Set or clear the active channel for a player.
     * If channelId is null/blank, the entry is removed.
     */
    public void setActiveChannel(UUID playerId, String channelId) {
        if (playerId == null) return;
        if (channelId == null || channelId.isBlank()) {
            activeChannel.remove(playerId);
        } else {
            activeChannel.put(playerId, channelId.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Get the active channel for the player, falling back to config default
     * (or "global" if config is missing).
     */
    public String getActiveChannel(UUID playerId) {
        if (playerId == null) return getDefaultChannelId();
        String current = activeChannel.get(playerId);
        return (current != null && !current.isBlank()) ? current : getDefaultChannelId();
    }

    /**
     * Clear state when player leaves.
     */
    public void clear(UUID playerId) {
        if (playerId != null) {
            activeChannel.remove(playerId);
        }
    }

    private String getDefaultChannelId() {
        ChannelsConfig cfg = ChatConfigManager.CHANNELS;
        if (cfg != null && cfg.settings != null && cfg.settings.defaultChannel != null) {
            return cfg.settings.defaultChannel.toLowerCase(Locale.ROOT);
        }
        return "global";
    }
}
