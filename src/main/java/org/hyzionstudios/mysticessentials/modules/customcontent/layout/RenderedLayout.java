package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one render pass produced: the addressable nodes, the inputs whose values
 * can be read back, and the labels a live refresh re-pushes.
 *
 * <p>Structure is built once when the UI opens. Refreshes reuse this record and
 * only re-send values, so a live-updating page never rebuilds its markup and
 * never loses scroll position or input focus.</p>
 */
public final class RenderedLayout {

    /** A label whose text is recomputed on every refresh. */
    record Live(UiNode node, String selector, boolean meta) {
    }

    /** A node whose visibility follows a requirement check. */
    record Gated(UiNode node, String selector) {
    }

    /** A downloaded portrait whose texture may become available after open. */
    record Portrait(String username, String slot, String selector) {
    }

    private final Map<String, UiNode> nodesBySelector = new LinkedHashMap<>();
    private final Map<String, String> inputSelectors = new LinkedHashMap<>();
    private final List<Live> live = new ArrayList<>();
    private final List<Gated> gated = new ArrayList<>();
    private final List<Portrait> portraits = new ArrayList<>();
    /** Ids this render actually emitted, so nothing addresses a stranger. */
    private final Set<String> emitted = new LinkedHashSet<>();
    private final List<String> problems = new ArrayList<>();

    void register(String selector, UiNode node) {
        nodesBySelector.put(selector, node);
    }

    void registerEmitted(String selector) {
        emitted.add(selector);
    }

    /**
     * @return true when this render created {@code selector}. Setting a
     *         property on an element the client cannot find disconnects the
     *         player, so the refresh loop checks before it addresses anything.
     */
    boolean emitted(String selector) {
        return emitted.contains(selector);
    }

    /** Records something skipped so the surface could still be shown safely. */
    void registerProblem(String problem) {
        problems.add(problem);
    }

    /** @return anything this render had to skip; empty when it rendered whole. */
    List<String> problems() {
        return problems;
    }

    void registerInput(String name, String selector) {
        if (name != null && !name.isBlank()) {
            inputSelectors.put(name, selector);
        }
    }

    void registerLive(UiNode node, String selector, boolean meta) {
        live.add(new Live(node, selector, meta));
    }

    void registerGated(UiNode node, String selector) {
        gated.add(new Gated(node, selector));
    }

    void registerPortrait(String username, String slot, String selector) {
        portraits.add(new Portrait(username, slot, selector));
    }

    /** @return the node an event payload refers to, or {@code null}. */
    UiNode node(String selector) {
        return selector == null ? null : nodesBySelector.get(selector);
    }

    /** @return input name to element selector, for reading values on activation. */
    Map<String, String> inputs() {
        return inputSelectors;
    }

    List<Live> live() {
        return live;
    }

    /** @return nodes whose visibility follows a requirement check. */
    List<Gated> gated() {
        return gated;
    }

    List<Portrait> portraits() {
        return portraits;
    }

    /** @return true when anything on the surface can change without a rebuild. */
    public boolean refreshable() {
        return !live.isEmpty() || !gated.isEmpty();
    }
}
