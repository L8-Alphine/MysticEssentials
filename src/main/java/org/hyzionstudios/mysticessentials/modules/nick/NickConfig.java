package org.hyzionstudios.mysticessentials.modules.nick;

import java.util.ArrayList;
import java.util.List;

/** Persisted settings for {@code modules/nick/config.json}. */
public final class NickConfig {

    public int configVersion = 1;

    /** Length limits apply to the visible name (colour codes stripped). */
    public int minLength = 3;
    public int maxLength = 16;

    /** Lowercase names players may not take (real usernames are always blocked for others). */
    public List<String> blockedNames = new ArrayList<>(List.of("admin", "owner", "server", "console"));

    /** Prefix shown before nicknames in chat so staff can tell them apart; empty disables. */
    public String nickMarker = "~";

    /**
     * How stored/displayed nicknames are composed. Supported placeholders:
     * {@code {marker}} and {@code {nickname}}. Set to {@code "{nickname}"} to
     * remove the default "~" marker while preserving nick colors.
     */
    public String nickFormat = "{marker}{nickname}";
}
