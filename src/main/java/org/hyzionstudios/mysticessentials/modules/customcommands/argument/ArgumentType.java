package org.hyzionstudios.mysticessentials.modules.customcommands.argument;

import java.util.Locale;

/**
 * The argument types a custom command definition may declare. Ids are the
 * lowercase strings owners write in {@code commands/*.json} (e.g.
 * {@code "greedy_string"}).
 */
public enum ArgumentType {

    /** One token; supports {@code "quoted strings"} spanning spaces. */
    STRING("string", "text"),
    /** One bare token, never quoted. */
    WORD("word", "word"),
    /** A decimal number. */
    NUMBER("number", "number"),
    /** A whole number. */
    INTEGER("integer", "whole number"),
    /** {@code true/false} (also accepts yes/no/on/off). */
    BOOLEAN("boolean", "true/false"),
    /** An online player's name; resolves to a live player at parse time. */
    PLAYER("player", "online player"),
    /** Any player name; resolved to the canonical name when online. */
    OFFLINE_PLAYER("offline_player", "player name"),
    /** A duration like {@code 90}, {@code 30s}, {@code 5m}, {@code 2h30m}, {@code 1d}; value in seconds. */
    DURATION("duration", "duration (e.g. 5m)"),
    /** The remainder of the input; must be the last argument. */
    GREEDY_STRING("greedy_string", "text...");

    private final String id;
    private final String expected;

    ArgumentType(String id, String expected) {
        this.id = id;
        this.expected = expected;
    }

    public String id() {
        return id;
    }

    /** Human-readable description of what a value must look like, for error messages. */
    public String expected() {
        return expected;
    }

    /** @return the type for a JSON id, or {@code null} if unknown. */
    public static ArgumentType fromId(String id) {
        if (id == null) {
            return null;
        }
        String needle = id.trim().toLowerCase(Locale.ROOT);
        for (ArgumentType type : values()) {
            if (type.id.equals(needle)) {
                return type;
            }
        }
        return null;
    }
}
