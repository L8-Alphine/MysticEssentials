package org.hyzionstudios.mysticessentials.core.notification;

/**
 * Vanilla sound-event ids used by the notification system.
 *
 * <p>Keep notification defaults here instead of scattering guessed ids through
 * module configs. Every value below is present in Hytale's sound AssetMap; the
 * delivery layer still resolves the id at runtime so a server/client version
 * mismatch only skips the sound surface.</p>
 */
public final class NotificationSounds {

    /** A light nudge for routine notices and personal messages. */
    public static final String QUIET = "SFX_Attn_Quiet";
    /** The default server-announcement sound. */
    public static final String ANNOUNCEMENT = "SFX_Attn_Moderate";
    /** A prominent warning/alert sound. */
    public static final String ALERT = "SFX_Attn_Loud";
    /** Reserved for critical and emergency notifications. */
    public static final String CRITICAL = "SFX_Attn_VeryLoud";

    private NotificationSounds() {
    }
}
