package org.hyzionstudios.mysticessentials.modules.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Appends channel moderation + ownership records to {@code logs/channel.log}
 * (design bible §23). Every state-changing management action — moderator changes,
 * mutes, removals, bans, ownership transfer and succession — is written here so the
 * actions remain auditable even before the MysticModeration integration (Phase 5).
 */
final class ChannelAudit {

    private final MysticCore core;
    private final Object lock = new Object();

    ChannelAudit(MysticCore core) {
        this.core = core;
    }

    /**
     * Records one action.
     *
     * @param action   short verb, e.g. {@code MODERATOR_ASSIGNED}, {@code OWNERSHIP_TRANSFERRED}
     * @param channelId the channel the action targets
     * @param actor    who performed the action ({@code null} for system/succession)
     * @param target   the affected member ({@code null} when not member-scoped)
     * @param detail   free-form context (reason, duration, previous/new value)
     */
    void record(String action, String channelId, UUID actor, UUID target, String detail) {
        StringBuilder line = new StringBuilder();
        line.append(Instant.now()).append(" | ");
        line.append("action=").append(action).append(" | ");
        line.append("channel=").append(channelId).append(" | ");
        line.append("actor=").append(actor == null ? "SYSTEM" : actor).append(" | ");
        if (target != null) {
            line.append("target=").append(target).append(" | ");
        }
        line.append("detail=").append(detail == null ? "" : detail);
        append(line.toString());
    }

    private void append(String line) {
        Path file = core.paths().logsDir().resolve("channel.log");
        synchronized (lock) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                core.log(Level.WARNING, "Failed to write channel audit log: " + e.getMessage());
            }
        }
    }
}
