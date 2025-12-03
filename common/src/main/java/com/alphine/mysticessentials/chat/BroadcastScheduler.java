package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.AnnouncementsConfig;
import com.alphine.mysticessentials.config.ChatConfigManager.AnnouncementsConfig.Group;

import java.util.*;
import java.util.function.Supplier;

/**
 * Auto-announcement scheduler.
 *
 * Usage:
 *   BroadcastScheduler scheduler = new BroadcastScheduler(
 *       () -> ChatConfigManager.ANNOUNCEMENTS,
 *       miniMessage -> {
 *           // TODO: broadcast via your chat system
 *           // e.g. chatModule.broadcastRaw(miniMessage);
 *       }
 *   );
 *
 *   // Then call scheduler.tick() once per second from your scheduler / tick loop.
 */
public final class BroadcastScheduler {

    private final Supplier<AnnouncementsConfig> configSupplier;
    private final ChatBroadcaster broadcaster;

    /**
     * Seconds remaining until the next broadcast.
     * We assume tick() is called once per second.
     */
    private int secondsUntilNextBroadcast = 0;

    /**
     * Priority index used when randomOrder == false.
     */
    private int priorityIndex = 0;

    /**
     * Per-group state: next message index when group.randomOrder == false.
     */
    private final Map<String, GroupState> groupStates = new HashMap<>();

    private final Random random = new Random();

    public BroadcastScheduler(Supplier<AnnouncementsConfig> configSupplier,
                              ChatBroadcaster broadcaster) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster");
        resetTimer();
    }

    /**
     * Call this once per second.
     */
    public void tick() {
        AnnouncementsConfig cfg = configSupplier.get();
        if (cfg == null || !cfg.enabled) {
            return;
        }

        if (secondsUntilNextBroadcast > 0) {
            secondsUntilNextBroadcast--;
            return;
        }

        Group group = selectGroup(cfg);
        if (group == null || !group.enabled || group.messages.isEmpty()) {
            // No suitable group; just delay again by the global interval.
            secondsUntilNextBroadcast = Math.max(1, cfg.intervalSeconds);
            return;
        }

        // Pick message from the group.
        String rawMessage = selectMessage(group);

        // Split into logical lines (supports <br> and real newlines).
        List<String> logicalLines = splitIntoLines(rawMessage);

        // Apply centering if enabled.
        if (group.center) {
            for (int i = 0; i < logicalLines.size(); i++) {
                logicalLines.set(i, centerLine(logicalLines.get(i)));
            }
        }

        // Apply group format and broadcast each line separately.
        for (String line : logicalLines) {
            String formatted = applyFormat(group, line);
            broadcaster.broadcast(formatted);
        }

        // Schedule next broadcast based on group / global interval.
        int interval = group.intervalSeconds > 0 ? group.intervalSeconds : cfg.intervalSeconds;
        secondsUntilNextBroadcast = Math.max(1, interval);
    }

    // ---------------------------------------------------------------------
    // Group / message selection
    // ---------------------------------------------------------------------

    private Group selectGroup(AnnouncementsConfig cfg) {
        // Gather enabled groups with at least one message.
        List<Group> enabled = new ArrayList<>();
        for (Group g : cfg.groups.values()) {
            if (g.enabled && g.messages != null && !g.messages.isEmpty()) {
                enabled.add(g);
            }
        }
        if (enabled.isEmpty()) {
            return null;
        }

        if (cfg.randomOrder) {
            // Pick a random group.
            return enabled.get(random.nextInt(enabled.size()));
        }

        // Priority-based selection.
        List<String> priorityIds = cfg.priority;
        if (priorityIds == null || priorityIds.isEmpty()) {
            // If no explicit priority list, just walk the enabled list in order.
            return enabled.get(0);
        }

        // Try to find the next valid group in the priority list.
        int attempts = 0;
        while (attempts < priorityIds.size()) {
            if (priorityIndex >= priorityIds.size()) {
                priorityIndex = 0;
            }
            String id = priorityIds.get(priorityIndex++);
            Group g = cfg.groups.get(id);
            if (g != null && g.enabled && g.messages != null && !g.messages.isEmpty()) {
                return g;
            }
            attempts++;
        }

        // Fallback: first enabled group.
        return enabled.get(0);
    }

    private String selectMessage(Group group) {
        List<String> messages = group.messages;
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        if (group.randomOrder) {
            return messages.get(random.nextInt(messages.size()));
        }

        GroupState state = groupStates.computeIfAbsent(group.id, k -> new GroupState());
        if (state.nextMessageIndex >= messages.size()) {
            state.nextMessageIndex = 0;
        }
        String msg = messages.get(state.nextMessageIndex);
        state.nextMessageIndex = (state.nextMessageIndex + 1) % messages.size();
        return msg;
    }

    // ---------------------------------------------------------------------
    // Formatting helpers
    // ---------------------------------------------------------------------

    /**
     * Converts <br> into newlines and splits into logical lines.
     */
    private List<String> splitIntoLines(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.singletonList("");
        }

        // Convert <br> tokens into actual newlines.
        String normalized = raw.replace("<br>", "\n");

        String[] arr = normalized.split("\\r?\\n", -1);
        List<String> result = new ArrayList<>(arr.length);
        for (String s : arr) {
            result.add(s);
        }
        return result;
    }

    /**
     * Simple centering: strips MiniMessage tags, estimates line length,
     * and adds spaces in front so the text is roughly centered.
     */
    private String centerLine(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }

        // Strip MiniMessage tags like <gray>, </gray>, <gradient:...>, etc.
        String stripped = line.replaceAll("<[^>]+>", "");
        // Also strip legacy &x codes if you ever use them.
        stripped = stripped.replaceAll("&[0-9a-fk-orA-FK-OR]", "");

        int visibleLength = stripped.length();
        if (visibleLength == 0) {
            return line;
        }

        // Target chat width in characters (approximate).
        final int TARGET = 80;
        int spaces = Math.max(0, (TARGET - visibleLength) / 2);

        StringBuilder sb = new StringBuilder(spaces + line.length());
        for (int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
        sb.append(line);
        return sb.toString();
    }

    private String applyFormat(Group group, String line) {
        String format = group.format;
        if (format == null || format.isEmpty()) {
            return line;
        }
        // Replace <message> token with the actual line.
        return format.replace("<message>", line);
    }

    // ---------------------------------------------------------------------
    // Timer helpers
    // ---------------------------------------------------------------------

    public void resetTimer() {
        AnnouncementsConfig cfg = configSupplier.get();
        if (cfg == null) {
            secondsUntilNextBroadcast = 0;
            return;
        }
        secondsUntilNextBroadcast = Math.max(1, cfg.intervalSeconds);
    }

    private static final class GroupState {
        int nextMessageIndex = 0;
    }
}
