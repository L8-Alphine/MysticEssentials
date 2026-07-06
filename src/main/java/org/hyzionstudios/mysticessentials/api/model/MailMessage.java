package org.hyzionstudios.mysticessentials.api.model;

import java.time.Instant;
import java.util.UUID;

/** A single mail item in a player's inbox. Owned by the Mail module. */
public final class MailMessage {

    private String id;
    private String senderUuid;
    private String senderName;
    private String body;
    private String sentDate;
    private boolean read;
    private String expiresDate;

    public MailMessage() {
    }

    public static MailMessage create(UUID sender, String senderName, String body) {
        MailMessage mail = new MailMessage();
        mail.id = UUID.randomUUID().toString();
        mail.senderUuid = sender == null ? null : sender.toString();
        mail.senderName = senderName;
        mail.body = body;
        mail.sentDate = Instant.now().toString();
        mail.read = false;
        return mail;
    }

    public String getId() {
        return id;
    }

    public String getSenderUuid() {
        return senderUuid;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getBody() {
        return body;
    }

    public String getSentDate() {
        return sentDate;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public String getExpiresDate() {
        return expiresDate;
    }

    public void setExpiresDate(String expiresDate) {
        this.expiresDate = expiresDate;
    }
}
