package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards every {@code .ui} document this module asks a client to append.
 *
 * <p>A Custom UI append naming a document the client does not have is not a
 * soft failure — the client drops the connection with
 * "Could not find document … for Custom UI Append command". A mod that appends
 * a HUD on join and ships a jar missing that file therefore makes the server
 * unjoinable. Checking the path against our own jar first cannot prove the
 * client received the asset pack, but it does turn a packaging regression from
 * a mass disconnect into a log line.</p>
 */
final class UiAssets {

    /** Every document the layout engine is allowed to append. */
    static final List<String> SHIPPED = List.of(
            UiDocument.Frame.CONTAINER.template,
            UiDocument.Frame.DECORATED.template,
            UiDocument.Frame.PLAIN.template,
            LayoutHud.SHELL,
            LayoutRenderer.TEMPLATE_TEXT_INPUT,
            LayoutRenderer.TEMPLATE_DROPDOWN,
            LayoutRenderer.TEMPLATE_PRIMARY,
            LayoutRenderer.TEMPLATE_SECONDARY,
            LayoutRenderer.TEMPLATE_TERTIARY);

    /** Append paths are relative to this resource root inside the jar. */
    private static final String ROOT = "/Common/UI/Custom/";

    /**
     * Documents must sit exactly one directory under {@code Common/UI/Custom/}.
     * A mod-pack document nested deeper cannot resolve its {@code ../../Common.ui}
     * import, and that failure is not contained: it poisons the client's whole
     * UI document registry, so documents belonging to OTHER packs stop
     * resolving and any mod appending one disconnects the player. Diagnosed
     * 2026-07-28 from paired client logs — a nested shell here made an
     * unrelated mod's HUD unresolvable at world join.
     */
    private static final int MAX_PATH_DEPTH = 2;

    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private UiAssets() {
    }

    /**
     * @return true when {@code path} is packaged in this jar and therefore safe
     *         to hand to {@code UICommandBuilder.append}
     */
    static boolean exists(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return CACHE.computeIfAbsent(path, key -> {
            try (var stream = UiAssets.class.getResourceAsStream(ROOT + key)) {
                return stream != null;
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** @return the shipped documents that are missing from the jar. */
    static List<String> missing() {
        return SHIPPED.stream().filter(path -> !exists(path)).toList();
    }

    /**
     * @return shipped documents nested too deeply to resolve their import; see
     *         {@link #MAX_PATH_DEPTH}. Any hit here is a packaging bug that
     *         breaks other mods, not just this one.
     */
    static List<String> tooDeep() {
        return SHIPPED.stream()
                .filter(path -> path.chars().filter(c -> c == '/').count() >= MAX_PATH_DEPTH)
                .toList();
    }

    /**
     * Builds the id prefix for one rendered surface, so generated ids cannot
     * collide with another surface's, with the shell's, or with another mod's.
     * Client-side selectors are matched by name, and nothing scopes them per
     * document — two overlays that both name an element {@code #HudRoot} are one
     * bug report waiting to happen.
     *
     * @return a prefix of alphanumerics only; no builtin id uses any other
     *         character, so this stays inside the grammar the client accepts
     */
    static String selectorPrefix(UiDocument document) {
        StringBuilder prefix = new StringBuilder("Me")
                .append(document.hud() ? 'H' : 'P');
        String id = document.id;
        for (int index = 0; index < id.length() && prefix.length() < 20; index++) {
            char character = id.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                prefix.append(prefix.length() == 3
                        ? Character.toUpperCase(character)
                        : Character.toLowerCase(character));
            }
        }
        return prefix.toString();
    }

    /** @return a stable, markup-safe element id for the shell of {@code kind}. */
    static String shellSelector(String kind) {
        return "#MysticLayout" + kind.substring(0, 1).toUpperCase(Locale.ROOT) + kind.substring(1);
    }
}
