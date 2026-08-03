package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * A screen overlay rendered from a {@link UiDocument}.
 *
 * <p>HUDs use the same layout tree as pages, minus interaction: the client
 * routes no events to a HUD, so documents rendered here are read-only
 * indicators. Their value is the refresh path — {@link #refresh()} re-sends
 * label values on a timer without redrawing the overlay.</p>
 */
public final class LayoutHud extends CustomUIHud {

    static final String SHELL = "Hud/MysticEssentialsLayout.ui";
    private static final String ROOT = "#MysticLayoutHudRoot";

    private final LayoutRuntime runtime;
    private final UiDocument document;
    private final PlayerRef viewer;
    private RenderedLayout layout;

    LayoutHud(PlayerRef player, LayoutRuntime runtime, UiDocument document) {
        super(player, key(document.id), document.hudZOrder);
        this.runtime = runtime;
        this.document = document;
        this.viewer = player;
    }

    /** @return the HUD manager key a document's overlay is registered under. */
    public static String key(String documentId) {
        return "mysticessentials:layout:" + documentId;
    }

    public UiDocument document() {
        return document;
    }

    PlayerRef viewer() {
        return viewer;
    }

    @Override
    protected void build(UICommandBuilder cmd) {
        if (!UiAssets.exists(SHELL)) {
            // Overlays are appended on join, so a missing shell here would fail
            // every connection rather than one page open.
            runtime.reportBrokenSurface(viewer, document, "HUD shell " + SHELL
                    + " is missing from the mod jar");
            return;
        }
        cmd.append(SHELL);
        cmd.setObject(ROOT + ".Anchor", rootAnchor());
        layout = LayoutRenderer.render(document, viewer, runtime.bridge(), runtime.portraits(), cmd, null, ROOT);
        if (runtime.portraits() != null) runtime.portraits().flush(viewer.getPacketHandler());
        runtime.reportProblems(document, layout);
    }

    /** Re-sends label values in place; the overlay itself is not rebuilt. */
    void refresh() {
        if (layout == null || !layout.refreshable()) {
            return;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        LayoutRenderer.refreshValues(layout, viewer, runtime.bridge(), cmd);
        update(false, cmd);
    }

    boolean refreshable() {
        return layout != null && layout.refreshable();
    }

    void refreshPortrait(String username) {
        if (layout == null || runtime.portraits() == null) return;
        UICommandBuilder cmd = new UICommandBuilder();
        LayoutRenderer.refreshPortraits(layout, viewer, runtime.portraits(), username, cmd);
        runtime.portraits().flush(viewer.getPacketHandler())
                .thenRun(() -> runtime.runOnViewerThread(viewer, () -> {
                    if (runtime.open(this)) update(false, cmd);
                }));
    }

    /**
     * Translates the document's anchor corner and offsets into the four-sided
     * anchor the client expects. Only the edges the corner touches are pinned,
     * so the overlay keeps its distance from that corner at any resolution.
     */
    private com.hypixel.hytale.server.core.ui.Anchor rootAnchor() {
        int x = document.hudOffsetX;
        int y = document.hudOffsetY;
        Integer width = document.width > 0 ? document.width : null;
        Integer height = document.height > 0 ? document.height : null;

        return switch (document.hudAnchor) {
            case TOP_LEFT -> LayoutRenderer.anchor(x, y, null, null, width, height);
            case TOP_CENTER -> LayoutRenderer.anchor(null, y, null, null, width, height);
            case TOP_RIGHT -> LayoutRenderer.anchor(null, y, x, null, width, height);
            case MIDDLE_LEFT -> LayoutRenderer.anchor(x, null, null, null, width, height);
            case CENTER -> LayoutRenderer.anchor(null, null, null, null, width, height);
            case MIDDLE_RIGHT -> LayoutRenderer.anchor(null, null, x, null, width, height);
            case BOTTOM_LEFT -> LayoutRenderer.anchor(x, null, null, y, width, height);
            case BOTTOM_CENTER -> LayoutRenderer.anchor(null, null, null, y, width, height);
            case BOTTOM_RIGHT -> LayoutRenderer.anchor(null, null, x, y, width, height);
        };
    }
}
