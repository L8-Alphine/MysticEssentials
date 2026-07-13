package org.hyzionstudios.mysticessentials.modules.tutorial.ui;

import java.util.Locale;

/** Actions a tutorial page button can perform (JSON {@code action.type}). */
public enum TutorialButtonActionType {
    CLOSE,
    PAGE,
    /** Runs the value as a command by the clicking player (alias: {@code player_command}). */
    COMMAND,
    PLAYER_COMMAND,
    CONSOLE_COMMAND,
    /** Starts the tutorial named in the value. */
    TUTORIAL,
    MESSAGE,
    URL,
    TELEPORT,
    SOUND;

    /** @return the parsed type, or {@code null} for unknown strings (logged and ignored). */
    public static TutorialButtonActionType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
