package org.hyzionstudios.mysticessentials.modules.tutorial.config;

/**
 * A button on a {@link TutorialPageDefinition}. The action {@code type} maps to
 * {@code ui.TutorialButtonActionType}; {@code value} is that action's argument
 * (page id, command line, tutorial id, message text, URL, or location string).
 */
public final class TutorialButtonDefinition {

    public String id;
    public String text = "";
    /** Icon hint; reserved for future asset-backed rendering. */
    public String icon = "";
    public Action action = new Action();

    public static final class Action {
        public String type = "close";
        public String value = "";
    }

    public boolean isValid() {
        return id != null && !id.isBlank() && action != null && action.type != null;
    }
}
