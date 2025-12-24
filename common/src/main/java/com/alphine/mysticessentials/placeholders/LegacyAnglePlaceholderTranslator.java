package com.alphine.mysticessentials.placeholders;

import java.util.Map;

public final class LegacyAnglePlaceholderTranslator {

    private static final Map<String, String> MAP = Map.ofEntries(
            Map.entry("<player>", "{player}"),
            Map.entry("<name>", "{player}"),
            Map.entry("<display-name>", "{player}"),

            Map.entry("<online>", "{online}"),
            Map.entry("<max>", "{max}"),

            Map.entry("<world>", "{world}"),
            Map.entry("<server>", "{platform}")
    );

    private LegacyAnglePlaceholderTranslator() {}

    public static String translate(String input) {
        if (input == null || input.isEmpty()) return input;

        String out = input;
        for (var e : MAP.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }
}
