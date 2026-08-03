package org.hyzionstudios.mysticessentials.modules.customcontent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Mutable JSON model used by the dialog builder. */
final class DialogDefinition {

    String id = "";
    String title = "";
    String npcName = "";
    String dialogWidth = "Full";
    List<Page> pages = new ArrayList<>();

    DialogDefinition() {
    }

    DialogDefinition(String id) {
        this.id = safeId(id);
    }

    void normalize(String mapId) {
        id = safeId(id == null || id.isBlank() ? mapId : id);
        title = text(title);
        npcName = text(npcName);
        dialogWidth = switch (text(dialogWidth).toLowerCase(Locale.ROOT)) {
            case "mid" -> "Mid";
            case "compact" -> "Compact";
            default -> "Full";
        };
        if (pages == null) {
            pages = new ArrayList<>();
        }
        pages.removeIf(java.util.Objects::isNull);
        pages.forEach(Page::normalize);
    }

    static String safeId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "_")
                .replaceAll("_+", "_");
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    static final class Page {
        String text = "";
        String continueLabel = "Continue";
        List<Button> buttons = new ArrayList<>();

        void normalize() {
            text = DialogDefinition.text(text);
            continueLabel = continueLabel == null || continueLabel.isBlank() ? "Continue" : continueLabel;
            if (buttons == null) {
                buttons = new ArrayList<>();
            }
            buttons.removeIf(java.util.Objects::isNull);
            buttons.forEach(Button::normalize);
        }
    }

    static final class Button {
        String text = "";
        List<String> actions = new ArrayList<>();

        void normalize() {
            text = DialogDefinition.text(text);
            if (actions == null) {
                actions = new ArrayList<>();
            }
            actions.removeIf(action -> action == null || action.isBlank());
            actions.replaceAll(String::trim);
        }
    }
}
