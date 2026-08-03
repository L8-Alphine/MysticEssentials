package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.util.Locale;

import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;

/**
 * A parsed {@code .gui.html} document: the window/HUD shell settings plus the
 * root of its layout tree. Documents are immutable once parsed; reloading
 * replaces the instance so open UIs keep rendering the tree they opened with.
 */
public final class UiDocument {

    /** Whether the document paints a window or a screen overlay. */
    public enum Surface {
        PAGE, HUD
    }

    /** Chrome drawn around a {@link Surface#PAGE} document. */
    public enum Frame {
        /** Standard Hytale window with a title bar. */
        CONTAINER("MysticEssentials/LayoutShell.ui"),
        /** Window with the decorated header and bottom filigree. */
        DECORATED("MysticEssentials/LayoutShellDecorated.ui"),
        /** Flat panel with no title bar, for fully custom layouts. */
        PLAIN("MysticEssentials/LayoutShellPlain.ui");

        final String template;

        Frame(String template) {
            this.template = template;
        }
    }

    /** Where a {@link Surface#HUD} document sits on screen. */
    public enum HudAnchor {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        MIDDLE_LEFT, CENTER, MIDDLE_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    public final String id;
    public final String command;
    public final Surface surface;
    public final Frame frame;
    public final CustomPageLifetime lifetime;
    public final String title;
    public final int width;
    public final int height;

    /** Seconds between live value refreshes; {@code 0} disables refreshing. */
    public final int refreshSeconds;
    /** Default "close the UI after an action" behaviour. */
    public final boolean defaultClose;
    /** Default "rebuild the UI after an action" behaviour. */
    public final boolean defaultRefresh;

    public final HudAnchor hudAnchor;
    public final int hudOffsetX;
    public final int hudOffsetY;
    public final int hudZOrder;
    /** Shows the HUD to every player as soon as the document loads. */
    public final boolean hudAutoShow;

    final UiNode root;

    UiDocument(Builder builder) {
        this.id = builder.id;
        this.command = builder.command;
        this.surface = builder.surface;
        this.frame = builder.frame;
        this.lifetime = builder.lifetime;
        this.title = builder.title;
        this.width = builder.width;
        this.height = builder.height;
        this.refreshSeconds = builder.refreshSeconds;
        this.defaultClose = builder.defaultClose;
        this.defaultRefresh = builder.defaultRefresh;
        this.hudAnchor = builder.hudAnchor;
        this.hudOffsetX = builder.hudOffsetX;
        this.hudOffsetY = builder.hudOffsetY;
        this.hudZOrder = builder.hudZOrder;
        this.hudAutoShow = builder.hudAutoShow;
        this.root = builder.root;
    }

    public boolean hud() {
        return surface == Surface.HUD;
    }

    /** @return the number of elements in the tree, for the load report. */
    public int nodeCount() {
        return root == null ? 0 : root.totalNodes();
    }

    static final class Builder {
        String id = "";
        String command;
        Surface surface = Surface.PAGE;
        Frame frame = Frame.CONTAINER;
        CustomPageLifetime lifetime = CustomPageLifetime.CanDismiss;
        String title = "";
        int width = UiTheme.DEFAULT_PAGE_WIDTH;
        int height = UiTheme.DEFAULT_PAGE_HEIGHT;
        int refreshSeconds;
        boolean defaultClose;
        boolean defaultRefresh = true;
        HudAnchor hudAnchor = HudAnchor.TOP_LEFT;
        int hudOffsetX = 20;
        int hudOffsetY = 20;
        int hudZOrder = 1;
        boolean hudAutoShow;
        UiNode root;
    }

    static Frame frame(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "container", "window", "default" -> Frame.CONTAINER;
            case "decorated", "fancy" -> Frame.DECORATED;
            case "plain", "flat", "none" -> Frame.PLAIN;
            default -> null;
        };
    }

    static CustomPageLifetime lifetime(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "dismiss", "closable" -> CustomPageLifetime.CanDismiss;
            case "interaction" -> CustomPageLifetime.CanDismissOrCloseThroughInteraction;
            case "locked", "cantclose" -> CustomPageLifetime.CantClose;
            default -> null;
        };
    }

    static HudAnchor hudAnchor(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(" ", "-")) {
            case "top-left", "topleft" -> HudAnchor.TOP_LEFT;
            case "top", "top-center", "topcenter" -> HudAnchor.TOP_CENTER;
            case "top-right", "topright" -> HudAnchor.TOP_RIGHT;
            case "left", "middle-left" -> HudAnchor.MIDDLE_LEFT;
            case "center", "middle" -> HudAnchor.CENTER;
            case "right", "middle-right" -> HudAnchor.MIDDLE_RIGHT;
            case "bottom-left", "bottomleft" -> HudAnchor.BOTTOM_LEFT;
            case "bottom", "bottom-center", "bottomcenter" -> HudAnchor.BOTTOM_CENTER;
            case "bottom-right", "bottomright" -> HudAnchor.BOTTOM_RIGHT;
            default -> null;
        };
    }
}
