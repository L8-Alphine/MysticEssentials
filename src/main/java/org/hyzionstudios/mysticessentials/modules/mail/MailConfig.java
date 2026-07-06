package org.hyzionstudios.mysticessentials.modules.mail;

/** Persisted mail settings for {@code modules/mail/config.json}. */
public final class MailConfig {

    /**
     * Maximum messages an inbox can hold (0 = unlimited). When full, the oldest
     * already-read message is dropped first; if none are read, the oldest overall.
     */
    public int maxInboxSize = 50;

    /** Maximum mail body length in characters (0 = unlimited); longer bodies are truncated. */
    public int maxMessageLength = 256;

    /** Tell players how much unread mail they have when they join. */
    public boolean notifyUnreadOnJoin = true;
}
