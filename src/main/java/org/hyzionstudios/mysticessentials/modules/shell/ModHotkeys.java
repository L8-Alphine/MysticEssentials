package org.hyzionstudios.mysticessentials.modules.shell;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.PluginBase;

/**
 * ALT+digit chords other installed mods already listen for.
 *
 * <p>A mod that reads ALT+digit the way {@link AltShortcuts} does sees the
 * same walk state and the same hotbar event, so two of them bound to one
 * digit both act on a single press: two pages race for the screen, and each
 * mod's slot restore reads to the other as a fresh chord. The rail leaves
 * such a digit to the other mod. Slot N still runs from {@code /N}, the rail
 * and the Shortcuts page.</p>
 *
 * <p>Mods are found by plugin id, never by version, and each one's own
 * setting decides the digit: turning the other mod's hotkey off, or moving
 * it, gives the digit back to the rail on the next reload.</p>
 */
final class ModHotkeys {

    /** A digit another mod owns. */
    record Hotkey(int digit, String mod, String action) {
    }

    /** Endgame &amp; QoL: ALT+2 opens its Journal unless its config says otherwise. */
    static final PluginIdentifier ENDGAME = new PluginIdentifier("Config", "Endgame&QoL");
    static final String ENDGAME_CONFIG = "EndgameConfig.json";
    static final int ENDGAME_DEFAULT_DIGIT = 2;

    private ModHotkeys() {
    }

    /** @return the digits installed mods bind, read from their own configs */
    static List<Hotkey> detect() {
        List<Hotkey> found = new ArrayList<>();
        PluginBase endgame = plugin(ENDGAME);
        if (endgame != null) {
            int digit = endgameJournalDigit(readObject(endgame.getDataDirectory().resolve(ENDGAME_CONFIG)));
            if (digit > 0) {
                found.add(new Hotkey(digit, "Endgame & QoL", "Journal"));
            }
        }
        return found;
    }

    /**
     * Endgame's own switch, {@code Misc.JournalHotkeyEnabled} and
     * {@code Misc.JournalHotkeyDigit}. A file that is missing (first boot,
     * before Endgame writes it) or unreadable means Endgame's defaults.
     *
     * @return the digit, 1–9, or 0 when Endgame's hotkey is off or bound past the hotbar
     */
    static int endgameJournalDigit(JsonObject config) {
        JsonObject misc = config != null && config.has("Misc") && config.get("Misc").isJsonObject()
                ? config.getAsJsonObject("Misc") : null;
        if (misc == null) {
            return ENDGAME_DEFAULT_DIGIT;
        }
        if (!bool(misc.get("JournalHotkeyEnabled"), true)) {
            return 0;
        }
        int digit = integer(misc.get("JournalHotkeyDigit"), ENDGAME_DEFAULT_DIGIT);
        return digit >= 1 && digit <= 9 ? digit : 0;
    }

    private static PluginBase plugin(PluginIdentifier id) {
        try {
            return HytaleServer.get().getPluginManager().getPlugin(id);
        } catch (Throwable ignored) {
            // No plugin manager yet: nothing to yield to.
            return null;
        }
    }

    private static JsonObject readObject(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            JsonElement element = Json.readFile(file);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean bool(JsonElement value, boolean fallback) {
        try {
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int integer(JsonElement value, int fallback) {
        try {
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
