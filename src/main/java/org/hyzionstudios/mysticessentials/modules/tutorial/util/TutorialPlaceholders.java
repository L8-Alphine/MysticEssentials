package org.hyzionstudios.mysticessentials.modules.tutorial.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialConfigLoader;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Tutorial-local placeholder pass applied to page text, button labels,
 * messages, and completion commands <i>before</i> the shared MessageService
 * pipeline (which adds PlaceholderAPI and colours on top):
 * {@code {player}}, {@code {uuid}}, {@code {tutorial}}, and
 * {@code {lang:key}} looked up in {@code localization/en_us.json}.
 */
public final class TutorialPlaceholders {

    private static final Pattern LANG = Pattern.compile("\\{lang:([^}]+)}");

    private TutorialPlaceholders() {
    }

    public static String apply(String text, PlayerRef player, String tutorialId,
            TutorialConfigLoader loader) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = text;
        if (loader != null && out.contains("{lang:")) {
            Matcher matcher = LANG.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(sb,
                        Matcher.quoteReplacement(loader.localize(matcher.group(1))));
            }
            matcher.appendTail(sb);
            out = sb.toString();
        }
        if (player != null) {
            out = out.replace("{player}", player.getUsername())
                    .replace("{uuid}", player.getUuid().toString());
        }
        return out.replace("{tutorial}", tutorialId == null ? "" : tutorialId);
    }
}
