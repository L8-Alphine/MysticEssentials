package org.hyzionstudios.mysticessentials.modules.nick;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Allow players with {@code mysticessentials.nick.color} to pick any custom
     * hex colour (via the colour picker or a typed {@code &#RRGGBB} / {@code <#RRGGBB>}
     * code). When {@code false}, only the named presets in {@link #colors} apply.
     */
    public boolean allowCustomHex = true;

    /**
     * Colour applied when a player with colour permission sets a nickname without
     * choosing one. A preset name from {@link #colors} or a hex like {@code #55FFFF}.
     * Empty leaves un-chosen nicknames uncoloured.
     */
    public String defaultColor = "";

    /**
     * Named colour presets, usable as {@code <name>} in the nickname field and as
     * {@link #defaultColor}. Players can also just type a hex code when
     * {@link #allowCustomHex} is enabled.
     */
    public Map<String, String> colors = defaultColors();

    private static Map<String, String> defaultColors() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("red", "#FF5555");
        map.put("orange", "#FFAA00");
        map.put("yellow", "#FFFF55");
        map.put("green", "#55FF55");
        map.put("aqua", "#55FFFF");
        map.put("blue", "#5555FF");
        map.put("purple", "#AA00AA");
        map.put("pink", "#FF55FF");
        map.put("white", "#FFFFFF");
        map.put("gray", "#AAAAAA");
        map.put("gold", "#FFAA00");
        return map;
    }
}
