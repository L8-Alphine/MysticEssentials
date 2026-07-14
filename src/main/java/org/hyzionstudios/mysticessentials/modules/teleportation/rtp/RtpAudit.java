package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.rtp.RtpResult;
import org.hyzionstudios.mysticessentials.api.rtp.RtpStatus;
import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Appends Random Teleport audit records to {@code logs/rtp.log} (spec §15):
 * who was teleported, by whom, which profile/world, the outcome, search cost,
 * payment, and whether bypasses were used. Exact coordinates are only written at
 * {@code FINE}-equivalent detail so lower-level staff viewers can be given the
 * summary line without positions.
 */
final class RtpAudit {

    private final MysticCore core;
    private final Object lock = new Object();

    RtpAudit(MysticCore core) {
        this.core = core;
    }

    void record(UUID player, String playerName, UUID actor, String actorName, RtpResult result,
            double amountPaid, boolean bypassesUsed) {
        StringBuilder line = new StringBuilder();
        line.append(Instant.now()).append(" | ");
        line.append("player=").append(playerName).append('(').append(player).append(") | ");
        if (actor != null && !actor.equals(player)) {
            line.append("actor=").append(actorName).append('(').append(actor).append(") | ");
        }
        line.append("profile=").append(result.profileId()).append(" | ");
        line.append("status=").append(result.status()).append(" | ");
        if (result.status() == RtpStatus.SUCCESS && result.destination() != null) {
            line.append("world=").append(result.destination().getWorld()).append(" | ");
            line.append("attempts=").append(result.attempts()).append(" | ");
            line.append("searchMs=").append(result.durationMillis()).append(" | ");
        } else if (result.cancelReason() != null) {
            line.append("cancel=").append(result.cancelReason()).append(" | ");
        } else if (result.detail() != null) {
            line.append("detail=").append(result.detail()).append(" | ");
        }
        line.append("paid=").append(amountPaid).append(" | ");
        line.append("bypasses=").append(bypassesUsed);
        append(line.toString());
    }

    private void append(String line) {
        Path file = core.paths().logsDir().resolve("rtp.log");
        synchronized (lock) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                core.log(Level.WARNING, "Failed to write RTP audit log: " + e.getMessage());
            }
        }
    }
}
