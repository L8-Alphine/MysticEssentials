package org.hyzionstudios.mysticessentials.modules.shell;

import java.util.Locale;

/**
 * What a shortcut slot does when it is activated.
 *
 * <p>The kind decides who owns the action, which is the bible's ownership rule:
 * the shell routes, the owning module authorises and performs. {@link #CHANNEL}
 * is handled inside Essentials by the chat module; everything else either opens
 * an Essentials page or hands off to a configured command, which is how a slot
 * reaches a module the shell knows nothing about.</p>
 */
public enum ShortcutKind {

    /** Switch the player's speaking channel (ALT+1–4 in the bible's table). */
    CHANNEL,
    /** Open the server store. Unconfigured servers show the slot as unavailable. */
    STORE,
    /** Open the built-in server wiki. */
    WIKI,
    /** Open the warp browser. */
    WARPS,
    /** Open the server browser / portal list. */
    SERVERS,
    /** Open the main menu. */
    MENU,
    /** Run a configured command as the player — the escape hatch for other mods. */
    COMMAND,
    /** Occupied but does nothing; renders as a disabled tile. */
    NONE;

    /** @return the named kind, or {@link #NONE} for anything unrecognised. */
    public static ShortcutKind parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return NONE;
        }
    }
}
