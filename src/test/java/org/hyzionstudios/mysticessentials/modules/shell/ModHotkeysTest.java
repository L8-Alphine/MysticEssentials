package org.hyzionstudios.mysticessentials.modules.shell;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Endgame &amp; QoL's own hotkey setting decides which ALT digit the rail
 * leaves to it.
 */
public final class ModHotkeysTest {

    public static void main(String[] args) {
        require(ModHotkeys.endgameJournalDigit(null) == 2, "no config file yet: Endgame's default, ALT+2");
        require(ModHotkeys.endgameJournalDigit(json("{}")) == 2, "no Misc section: the default");
        require(ModHotkeys.endgameJournalDigit(json("{\"Misc\": {\"JournalHotkeyEnabled\": true, \"JournalHotkeyDigit\": 2}}")) == 2,
                "the shipped setting");
        require(ModHotkeys.endgameJournalDigit(json("{\"Misc\": {\"JournalHotkeyEnabled\": false, \"JournalHotkeyDigit\": 2}}")) == 0,
                "Endgame's hotkey turned off gives the digit back");
        require(ModHotkeys.endgameJournalDigit(json("{\"Misc\": {\"JournalHotkeyDigit\": 7}}")) == 7,
                "a moved hotkey takes its new digit");
        require(ModHotkeys.endgameJournalDigit(json("{\"Misc\": {\"JournalHotkeyDigit\": 0}}")) == 0,
                "a digit off the hotbar's 1-9 needs nothing yielded");
        require(ModHotkeys.endgameJournalDigit(json("{\"Misc\": {\"JournalHotkeyDigit\": \"two\"}}")) == 2,
                "an unreadable digit falls back to the default");
        System.out.println("ModHotkeysTest: all checks passed.");
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
