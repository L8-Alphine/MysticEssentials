package org.hyzionstudios.mysticessentials.modules.mail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.model.MailAttachment;
import org.hyzionstudios.mysticessentials.api.model.MailMessage;

/**
 * One entry in the global mail-announcement history (namespace
 * {@code mail_announcements}). Records what was sent, to whom, and by whom, so
 * the admin center can list past announcements and re-send them.
 */
final class SentAnnouncement {

    String id;
    String senderName;
    String date;
    /** {@link MailModule.Audience} name. */
    String audience;
    /** Permission node or player name for the PERMISSION/PLAYER audiences (else blank). */
    String param;
    int recipientCount;
    String subject;
    String body;
    List<MailAttachment> items = new ArrayList<>();
    List<String> commands = new ArrayList<>();

    SentAnnouncement() {
    }

    static SentAnnouncement of(String senderName, MailModule.Audience audience, String param,
            int recipientCount, MailMessage prototype) {
        SentAnnouncement entry = new SentAnnouncement();
        entry.id = UUID.randomUUID().toString();
        entry.senderName = senderName;
        entry.date = Instant.now().toString();
        entry.audience = audience.name();
        entry.param = param == null ? "" : param;
        entry.recipientCount = recipientCount;
        entry.subject = prototype.getSubject();
        entry.body = prototype.getBody();
        entry.items = new ArrayList<>();
        for (MailAttachment item : prototype.items()) {
            entry.items.add(item.copy());
        }
        entry.commands = new ArrayList<>(prototype.commands());
        return entry;
    }

    List<MailAttachment> items() {
        return items == null ? List.of() : items;
    }

    List<String> commands() {
        return commands == null ? List.of() : commands;
    }
}
