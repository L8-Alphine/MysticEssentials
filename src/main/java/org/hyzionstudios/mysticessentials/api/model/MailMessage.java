package org.hyzionstudios.mysticessentials.api.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A single mail item in a player's inbox. Owned by the Mail module. */
public final class MailMessage {

    /** Mail category. {@code "normal"} = player/staff mail; {@code "announcement"} = admin broadcast mail. */
    public static final String TYPE_NORMAL = "normal";
    public static final String TYPE_ANNOUNCEMENT = "announcement";

    private String id;
    private String senderUuid;
    private String senderName;
    /** Recipient label, only set on copies stored in a sender's Sent folder. */
    private String recipientName;
    private String subject;
    private String body;
    private String type = TYPE_NORMAL;
    private String sentDate;
    private boolean read;
    private boolean archived;
    private boolean deleted;
    private boolean claimed;
    private String expiresDate;
    /** Reward item stacks escrowed on this mail; empty when the mail carries no items. */
    private List<MailAttachment> items = new ArrayList<>();
    /** Reward commands run once on claim (announcements only); empty for normal mail. */
    private List<String> commands = new ArrayList<>();

    public MailMessage() {
    }

    public static MailMessage create(UUID sender, String senderName, String body) {
        MailMessage mail = new MailMessage();
        mail.id = UUID.randomUUID().toString();
        mail.senderUuid = sender == null ? null : sender.toString();
        mail.senderName = senderName;
        mail.body = body;
        mail.type = TYPE_NORMAL;
        mail.sentDate = Instant.now().toString();
        mail.read = false;
        return mail;
    }

    /**
     * Returns a delivery copy of this prototype with a fresh id and delivery date,
     * reset per-recipient state (unread, unclaimed, not archived/deleted), and
     * deep-copied rewards so each recipient gets an independent escrow.
     */
    public MailMessage copyForDelivery() {
        MailMessage mail = new MailMessage();
        mail.id = UUID.randomUUID().toString();
        mail.senderUuid = senderUuid;
        mail.senderName = senderName;
        mail.subject = subject;
        mail.body = body;
        mail.type = type == null ? TYPE_NORMAL : type;
        mail.sentDate = Instant.now().toString();
        mail.expiresDate = expiresDate;
        mail.read = false;
        mail.archived = false;
        mail.deleted = false;
        mail.claimed = false;
        mail.items = new ArrayList<>();
        for (MailAttachment item : items()) {
            mail.items.add(item.copy());
        }
        mail.commands = new ArrayList<>(commands());
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

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public String getType() {
        return type == null ? TYPE_NORMAL : type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isAnnouncement() {
        return TYPE_ANNOUNCEMENT.equals(type);
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

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public boolean isClaimed() {
        return claimed;
    }

    public void setClaimed(boolean claimed) {
        this.claimed = claimed;
    }

    public String getExpiresDate() {
        return expiresDate;
    }

    public void setExpiresDate(String expiresDate) {
        this.expiresDate = expiresDate;
    }

    /** Never {@code null}; gson may leave the field null when deserializing old records. */
    public List<MailAttachment> items() {
        if (items == null) {
            items = new ArrayList<>();
        }
        return items;
    }

    public void setItems(List<MailAttachment> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }

    /** Never {@code null}; gson may leave the field null when deserializing old records. */
    public List<String> commands() {
        if (commands == null) {
            commands = new ArrayList<>();
        }
        return commands;
    }

    public void setCommands(List<String> commands) {
        this.commands = commands == null ? new ArrayList<>() : commands;
    }

    /** @return {@code true} if this mail carries unclaimed item or command rewards. */
    public boolean hasRewards() {
        return !items().isEmpty() || !commands().isEmpty();
    }

    /** @return {@code true} if there is something left to claim (has rewards and not yet claimed). */
    public boolean isClaimable() {
        return !claimed && hasRewards();
    }
}
