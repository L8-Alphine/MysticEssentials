package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * One element of a compiled layout tree.
 *
 * <p>Nodes are immutable once parsed and shared by every player who opens the
 * document, so nothing viewer-specific — selectors, toggle state — is stored
 * here; that lives in the per-render {@link RenderedLayout}.</p>
 *
 * <p>A node carries three separable concerns: its {@link Kind} and
 * {@link UiStyle} decide the markup emitted once when the page opens, its text
 * fields are pushed as runtime values (never inlined into markup, so author
 * text can never break the markup grammar), and its action lists drive the
 * event bindings.</p>
 */
public final class UiNode {

    /** What an element becomes in the client tree. */
    public enum Kind {
        /** Plain container; emits a {@code Group}. */
        CONTAINER,
        /** Container with panel chrome and optional heading. */
        PANEL,
        /** Non-interactive text run; emits a {@code Label}. */
        TEXT,
        /** Emphasised text run with heading metrics. */
        HEADING,
        /** Small uppercase divider label used for sidebar sections. */
        SECTION,
        /** Clickable container; emits a {@code Button} that may hold children. */
        BUTTON,
        /** Hytale-chromed text button appended from a template file. */
        CHROME_BUTTON,
        /** On/off row that runs one of two action lists. */
        TOGGLE,
        /** Dropdown appended from a template file. */
        DROPDOWN,
        /** Free text input appended from a template file. */
        FIELD,
        /** Search input paired with a submit button. */
        SEARCH,
        /** Item icon with an optional quantity. */
        ITEM,
        /** Standalone image. */
        IMAGE,
        /** Horizontal rule. */
        SEPARATOR,
        /** Fixed empty space. */
        SPACER,
        /** Value bar appended from a template file. */
        PROGRESS
    }

    /** One selectable entry of a {@link Kind#DROPDOWN} node. */
    public record Option(String value, String label, List<String> actions, List<String> requirements,
            Boolean close, Boolean refresh) {
    }

    final Kind kind;
    final UiStyle style;
    final List<UiNode> children = new ArrayList<>();

    /** Primary label text, before placeholder substitution. */
    String text = "";
    /** Secondary label text (card subtitles, toggle state, item quantity). */
    String meta = "";
    /** Author-facing name used to address inputs from action strings. */
    String name = "";
    String placeholder = "";
    /** Item id for {@link Kind#ITEM}, texture path for {@link Kind#IMAGE}. */
    String resource = "";
    /** Progress fraction in {@code [0,1]} for {@link Kind#PROGRESS}. */
    double value;
    /** Template variant for chromed widgets ({@code primary}/{@code secondary}/{@code tertiary}). */
    String variant = "";

    List<String> actions = List.of();
    List<String> alternateActions = List.of();
    List<String> requirements = List.of();
    List<String> clickRequirements = List.of();
    List<String> stateRequirements = List.of();
    Boolean close;
    Boolean refresh;
    boolean active;
    List<Option> options = List.of();

    UiNode(Kind kind, UiStyle style) {
        this.kind = kind;
        this.style = style;
    }

    boolean interactive() {
        return switch (kind) {
            case BUTTON, CHROME_BUTTON, TOGGLE, ITEM -> !actions.isEmpty() || !alternateActions.isEmpty();
            default -> false;
        };
    }

    /** @return true when the node's own markup is a container others nest into. */
    boolean container() {
        return switch (kind) {
            case CONTAINER, PANEL, BUTTON -> true;
            default -> false;
        };
    }

    /** @return true when the node carries text that placeholder refreshes update. */
    boolean dynamicText() {
        return !text.isBlank() || !meta.isBlank();
    }

    int totalNodes() {
        int total = 1;
        for (UiNode child : children) {
            total += child.totalNodes();
        }
        return total;
    }
}
