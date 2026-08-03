package org.hyzionstudios.mysticessentials.modules.chat.roster;

/**
 * What a member is currently doing (design bible §2.1 "Live Activity"), kept
 * separate from authority and participation.
 *
 * <p>Activity is inherently live state. On Hytale 0.5.6 the server cannot push
 * updates to an open Custom UI, so the roster reflects activity as of the moment it
 * is (re)opened rather than streaming it. {@link #SPEAKING} is only ever produced
 * when a registered {@code ChannelVoicePresenceProvider} confirms it (§11.4); with
 * no voice provider, text channels fall back to {@link #RECENTLY_ACTIVE} /
 * {@link #LISTENING} / {@link #IDLE}. {@link #TYPING}, {@link #DISCONNECTED} and
 * {@link #RECONNECTING} are modelled for completeness and for voice/cross-server
 * phases; the client has no typing signal in 0.5.6.</p>
 */
public enum ChannelActivity {

    SPEAKING("Speaking"),
    TYPING("Typing"),
    RECENTLY_ACTIVE("Recently active"),
    LISTENING("Listening"),
    IDLE("Idle"),
    RECONNECTING("Reconnecting"),
    DISCONNECTED("Disconnected");

    private final String label;

    ChannelActivity(String label) {
        this.label = label;
    }

    /** Human-readable label for the roster subtitle and details panel (§26). */
    public String label() {
        return label;
    }
}
