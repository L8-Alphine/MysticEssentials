package org.hyzionstudios.mysticessentials.modules.chat.roster;

/**
 * Whether a member may communicate through a channel (design bible §11), kept
 * separate from {@link ChannelMemberRole authority} and from live activity.
 *
 * <p>Phase 1 tracks only the <em>text</em> participation axis. For text channels a
 * {@link #SPEAKER} may send messages and a {@link #LISTENER} may only read them;
 * {@link #MUTED} is a listener who has been prevented from sending by channel
 * moderation (produced from Phase 2 onward). Voice participation is a separate
 * axis added in a later phase and is never conflated with this one (§11.3).</p>
 */
public enum ChannelParticipation {

    /** May send messages in the channel. */
    SPEAKER("Speaker"),

    /** May read the channel but not send. */
    LISTENER("Listener"),

    /** May read but has been temporarily prevented from sending by moderation. */
    MUTED("Channel Muted");

    private final String label;

    ChannelParticipation(String label) {
        this.label = label;
    }

    /** Human-readable label for tooltips and the details panel (§26 accessibility). */
    public String label() {
        return label;
    }
}
