package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Owns every open layout surface and drives their live refreshes.
 *
 * <p>Refreshing is value-only: the tick pushes new label text and gated
 * visibility to surfaces that are already open, so a document with a one second
 * interval costs one small packet per viewer per second and never disturbs
 * scroll position or a field being typed into.</p>
 */
public final class LayoutRuntime {

    /** Granularity of the refresh loop; document intervals are multiples of it. */
    private static final long TICK_SECONDS = 1;

    private final MysticCore core;
    private final LayoutBridge bridge;
    private final Function<String, UiDocument> documents;
    private final LayoutActions actions;
    private final PlayerPortraitService portraits;
    private final Map<LayoutPage, AtomicInteger> pages = new ConcurrentHashMap<>();
    private final Map<LayoutHud, AtomicInteger> huds = new ConcurrentHashMap<>();
    private final Set<LayoutPage> openPages = ConcurrentHashMap.newKeySet();
    private final Set<LayoutHud> openHuds = ConcurrentHashMap.newKeySet();
    private ScheduledFuture<?> ticker;

    /**
     * @param documents resolves a document id, so an {@code opengui} action can
     *                  navigate to another surface
     */
    public LayoutRuntime(MysticCore core, LayoutBridge bridge, Function<String, UiDocument> documents) {
        this(core, bridge, documents, null);
    }

    public LayoutRuntime(MysticCore core, LayoutBridge bridge, Function<String, UiDocument> documents,
            PlayerPortraitService portraits) {
        this.core = core;
        this.bridge = bridge;
        this.documents = documents;
        this.portraits = portraits;
        this.actions = new LayoutActions(core, this, bridge);
    }

    LayoutBridge bridge() {
        return bridge;
    }

    PlayerPortraitService portraits() {
        return portraits;
    }

    /** @return the compiled document for {@code id}, or {@code null}. */
    UiDocument document(String id) {
        return id == null || id.isBlank() ? null : documents.apply(id);
    }

    LayoutActions actions() {
        return actions;
    }

    /**
     * Reports a surface that could not be rendered at all, instead of sending
     * commands that would drop the player's connection.
     */
    void reportBrokenSurface(PlayerRef player, UiDocument document, String reason) {
        core.log(Level.SEVERE, "[customcontent] Refusing to render '" + document.id + "': " + reason
                + ". Reinstall the mod jar — sending this to the client would disconnect them.");
        if (player != null) {
            core.getMessageService().send(player,
                    "&cThis interface could not be shown. The server owner has details in the log.");
        }
    }

    /** Logs anything a render had to skip, once per surface rather than per element. */
    void reportProblems(UiDocument document, RenderedLayout layout) {
        if (layout == null || layout.problems().isEmpty()) {
            return;
        }
        core.log(Level.WARNING, "[customcontent] '" + document.id + "' rendered with "
                + layout.problems().size() + " element(s) skipped: " + layout.problems());
    }

    /**
     * Verifies that every document the engine can append is present in the jar.
     * Run once at load: a missing shell is a packaging fault that would
     * otherwise surface as players being unable to connect.
     *
     * @return the missing paths, empty when the install is intact
     */
    public List<String> verifyAssets() {
        List<String> missing = UiAssets.missing();
        if (!missing.isEmpty()) {
            core.log(Level.SEVERE, "[customcontent] Mod jar is missing UI documents " + missing
                    + ". Layout GUIs and HUDs are disabled until this is fixed.");
        }
        List<String> tooDeep = UiAssets.tooDeep();
        if (!tooDeep.isEmpty()) {
            core.log(Level.SEVERE, "[customcontent] UI documents " + tooDeep
                    + " are nested too deeply under Common/UI/Custom/. Documents must sit exactly"
                    + " one directory down, or the client fails to load them AND stops resolving"
                    + " documents belonging to other mods.");
        }
        return missing;
    }

    /** @return false when a shipped document is missing and surfaces must not open. */
    public boolean healthy() {
        return UiAssets.missing().isEmpty();
    }

    /** Starts the refresh loop. Safe to call repeatedly. */
    public void start() {
        if (ticker == null) {
            ticker = core.scheduler().runRepeating(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Stops the refresh loop and forgets every tracked surface. */
    public void stop() {
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
        pages.clear();
        huds.clear();
        openPages.clear();
        openHuds.clear();
    }

    /** Opens {@code document} as a window for {@code player}. */
    public void openPage(PlayerRef player, UiDocument document) {
        if (!healthy()) {
            reportBrokenSurface(player, document, "the mod jar is missing UI documents");
            return;
        }
        core.getCustomUiService().sessions().open(player.getUuid()).navigate(document.id, null);
        core.platform().openPage(player, new LayoutPage(core, player, this, document));
    }

    /** Shows {@code document} as an overlay for {@code player}. */
    public void showHud(PlayerRef player, UiDocument document) {
        if (!healthy()) {
            reportBrokenSurface(player, document, "the mod jar is missing UI documents");
            return;
        }
        LayoutHud hud = new LayoutHud(player, this, document);
        core.getCustomUiService().sessions().open(player.getUuid()).showHud(document.id);
        huds.keySet().removeIf(existing -> existing.getKey().equals(hud.getKey())
                && sameViewer(existing.viewer(), player));
        openHuds.removeIf(existing -> existing.getKey().equals(hud.getKey())
                && sameViewer(existing.viewer(), player));
        if (core.platform().showHud(player, hud)) {
            openHuds.add(hud);
            if (document.refreshSeconds > 0) {
                huds.put(hud, new AtomicInteger());
            }
        }
    }

    /** Hides the overlay for {@code documentId}, if the player has it. */
    public void hideHud(PlayerRef player, String documentId) {
        String key = LayoutHud.key(documentId);
        huds.keySet().removeIf(hud -> hud.getKey().equals(key) && sameViewer(hud.viewer(), player));
        openHuds.removeIf(hud -> hud.getKey().equals(key) && sameViewer(hud.viewer(), player));
        core.getCustomUiService().sessions().find(player.getUuid()).ifPresent(session -> session.hideHud(documentId));
        core.platform().removeHud(player, key);
    }

    /** Hides every overlay this runtime is showing, for every viewer. */
    public void hideAllHuds() {
        for (LayoutHud hud : List.copyOf(huds.keySet())) {
            core.platform().removeHud(hud.viewer(), hud.getKey());
        }
        huds.clear();
        openHuds.clear();
    }

    void trackPage(LayoutPage page) {
        openPages.add(page);
        if (page.document().refreshSeconds > 0 && page.refreshable()) {
            pages.put(page, new AtomicInteger());
        }
    }

    void untrackPage(LayoutPage page) {
        pages.remove(page);
        openPages.remove(page);
    }

    /** Called by the downloader when a previously missing portrait is cached. */
    public void portraitAvailable(String username) {
        if (portraits == null || username == null) return;
        for (LayoutPage page : List.copyOf(openPages)) {
            core.platform().runOnEntityThread(page.viewer(),
                    (store, entity, world) -> page.refreshPortrait(username));
        }
        for (LayoutHud hud : List.copyOf(openHuds)) {
            core.platform().runOnEntityThread(hud.viewer(),
                    (store, entity, world) -> hud.refreshPortrait(username));
        }
    }

    void runOnViewerThread(PlayerRef viewer, Runnable action) {
        core.platform().runOnEntityThread(viewer, (store, entity, world) -> action.run());
    }

    boolean open(LayoutPage page) {
        return openPages.contains(page);
    }

    boolean open(LayoutHud hud) {
        return openHuds.contains(hud);
    }

    private void tick() {
        try {
            refreshDue(pages, LayoutPage::document, page -> core.platform()
                    .runOnEntityThread(page.viewer(), (store, entity, world) -> page.refresh()));
            refreshDue(huds, LayoutHud::document, hud -> core.platform()
                    .runOnEntityThread(hud.viewer(), (store, entity, world) -> hud.refresh()));
        } catch (Throwable t) {
            core.log(Level.WARNING, "[customcontent] Layout refresh failed: " + t);
        }
    }

    /**
     * Advances each surface's counter and refreshes the ones whose interval has
     * elapsed. Surfaces whose viewer has gone offline are dropped.
     */
    private <T> void refreshDue(Map<T, AtomicInteger> tracked,
            Function<T, UiDocument> documentOf, Consumer<T> refresh) {
        for (Map.Entry<T, AtomicInteger> entry : Map.copyOf(tracked).entrySet()) {
            UiDocument document = documentOf.apply(entry.getKey());
            if (document.refreshSeconds <= 0) {
                tracked.remove(entry.getKey());
                continue;
            }
            if (entry.getValue().incrementAndGet() < document.refreshSeconds) {
                continue;
            }
            entry.getValue().set(0);
            refresh.accept(entry.getKey());
        }
    }

    private static boolean sameViewer(PlayerRef left, PlayerRef right) {
        return left != null && right != null && left.getUuid().equals(right.getUuid());
    }
}
