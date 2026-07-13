package org.hyzionstudios.mysticessentials.modules.tutorial.config;

import java.util.ArrayList;
import java.util.List;

/**
 * A tutorial UI page loaded from
 * {@code mods/MysticEssentials/modules/tutorial/pages/<page-id>.json}.
 * Rendered by {@code ui.TutorialPageRenderer} on the shared
 * {@code TutorialPage.ui} layout.
 */
public final class TutorialPageDefinition {

    public String id;
    public String title = "";
    public String subtitle = "";
    /** Layout hint; only {@code centered_cards} is rendered in the MVP. */
    public String layout = "centered_cards";

    public List<ContentItem> content = new ArrayList<>();
    public List<TutorialButtonDefinition> buttons = new ArrayList<>();

    public static final class ContentItem {
        /** Only {@code text} is supported in the MVP; unknown types are skipped. */
        public String type = "text";
        public String text = "";
    }

    public boolean isValid() {
        return id != null && !id.isBlank();
    }
}
