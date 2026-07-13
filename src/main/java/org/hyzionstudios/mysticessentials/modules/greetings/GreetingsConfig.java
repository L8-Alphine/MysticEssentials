package org.hyzionstudios.mysticessentials.modules.greetings;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted settings for {@code modules/greetings/config.json}: MOTD, first-join
 * message, and join/leave broadcasts.
 *
 * <p>Join and leave broadcasts default to <b>off</b> so they do not duplicate a
 * server-native or other-mod join/leave system; enable them when Mystic should
 * own those messages.</p>
 */
public final class GreetingsConfig {

    public boolean motdEnabled = true;
    public List<String> motd = defaultMotd();

    public boolean firstJoinEnabled = true;
    public String firstJoinMessage = "&e&lWelcome &f{player_name} &e&lto the server for the first time!";

    public boolean joinEnabled = false;
    public String joinMessage = "&8[&a+&8] &7{player_name}";

    public boolean leaveEnabled = false;
    public String leaveMessage = "&8[&c-&8] &7{player_name}";

    private static List<String> defaultMotd() {
        List<String> lines = new ArrayList<>();
        lines.add("<gradient:#7b2cff:#00d4ff>&lMystic Essentials</gradient>");
        lines.add("&7Welcome back, &f{player_name}&7!");
        lines.add("&7Type &f/mystic &7for help.");
        return lines;
    }
}
