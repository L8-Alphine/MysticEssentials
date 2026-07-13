package org.hyzionstudios.mysticessentials.modules.mail;

/** Persisted mail settings for {@code modules/mail/config.json}. */
public final class MailConfig {

    /**
     * Maximum messages an inbox can hold (0 = unlimited). When full, the oldest
     * already-read message is dropped first; if none are read, the oldest overall.
     */
    public int maxInboxSize = 50;

    /** Maximum mail body length in characters (0 = unlimited); longer bodies are truncated. */
    public int maxMessageLength = 2000;

    /** Tell players how much unread mail they have when they join. */
    public boolean notifyUnreadOnJoin = true;

    /** Allow normal players to attach items (from their own inventory) to mail. */
    public boolean allowPlayerItemAttachments = true;

    /** Maximum item stacks that can be attached to a single mail (0 = unlimited). */
    public int maxAttachments = 9;

    /** Allow admin announcements to carry reward commands (run as console on claim). */
    public boolean allowAnnouncementCommands = true;

    /** Item ids that may never be mailed (case-insensitive; empty = allow all). */
    public java.util.List<String> blockedItemIds = new java.util.ArrayList<>();

    /** How many mail rows to show per folder page in the UI. */
    public int pageSize = 6;

    /**
     * How many recipients an admin broadcast delivers to per batch (large-server
     * safety). Batches are delivered sequentially so a broadcast to thousands of
     * offline players never fires thousands of concurrent storage writes at once.
     */
    public int broadcastBatchSize = 50;
}
