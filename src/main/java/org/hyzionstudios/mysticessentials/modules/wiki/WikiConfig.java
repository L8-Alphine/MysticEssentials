package org.hyzionstudios.mysticessentials.modules.wiki;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed model of {@code mods/MysticEssentials/modules/wiki/config.json}.
 *
 * <p>Articles themselves live in {@code data/modules/wiki/articles.json}, not
 * here: they are content, they change constantly once staff start writing, and
 * a config file that is rewritten on every edit is a config file that loses
 * comments and fights with {@code /mystic reload}.</p>
 */
public final class WikiConfig {

    public int configVersion = 1;

    /** Window title. */
    public String title = "Server Wiki";
    /** One line under the title. */
    public String subtitle = "How this server works.";

    /**
     * Category order in the browser. Categories not listed here follow, sorted
     * alphabetically, so a new category appears without a config edit.
     */
    public List<String> categoryOrder = new ArrayList<>(
            List.of("Getting Started", "Rules", "Commands", "Economy", "Guides"));

    /** Whether other mods' contributed pages appear alongside stored ones. */
    public boolean includeModEntries = true;

    /**
     * Seed the wiki with a starter page on first run, so a fresh server opens
     * on something other than an empty list. Written once; deleting the page
     * does not bring it back.
     */
    public boolean seedStarterArticle = true;
}
