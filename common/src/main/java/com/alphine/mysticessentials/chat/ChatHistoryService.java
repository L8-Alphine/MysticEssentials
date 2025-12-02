package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.HistoryConfig;

import java.util.*;

public class ChatHistoryService {

    // Per-player ring buffer. Adjust as needed.
    private final Map<UUID, Deque<ChatHistoryEntry>> history = new HashMap<>();

    public void record(ChatContext ctx) {
        HistoryConfig cfg = ChatConfigManager.HISTORY;
        if (cfg == null || !cfg.enabled) return;
        if (!cfg.trackedMessageTypes.contains(ctx.messageType)) return;

        UUID uuid = ctx.sender.getUuid();
        Deque<ChatHistoryEntry> queue = history.computeIfAbsent(uuid, u -> new ArrayDeque<>());

        queue.addLast(new ChatHistoryEntry(ctx.timestamp, ctx.channelId, ctx.processedMessage));

        while (queue.size() > cfg.maxMessagesPerPlayer) {
            queue.pollFirst();
        }
    }

    public List<ChatHistoryEntry> getHistory(UUID uuid) {
        Deque<ChatHistoryEntry> q = history.get(uuid);
        return q == null ? List.of() : new ArrayList<>(q);
    }

    public static record ChatHistoryEntry(java.time.Instant time, String channelId, String message) {}
}
