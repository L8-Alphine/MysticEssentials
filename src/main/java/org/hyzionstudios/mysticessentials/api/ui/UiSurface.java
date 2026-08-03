package org.hyzionstudios.mysticessentials.api.ui;

import java.util.Locale;

/** Lifecycle and rendering category of a Custom UI document. */
public enum UiSurface {
    PAGE, DIALOG, MODAL, OVERLAY, HUD, ITEM_HUD, TOAST, TOOLTIP,
    CONTEXT_MENU, FRAGMENT, WINDOW, CINEMATIC;

    public static UiSurface parse(String value) {
        if (value == null || value.isBlank()) {
            return PAGE;
        }
        try {
            return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean overlayLike() {
        return this == HUD || this == ITEM_HUD || this == OVERLAY || this == TOAST;
    }
}
