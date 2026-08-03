package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.util.ArrayList;
import java.util.List;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Walks a compiled layout tree and turns it into client commands.
 *
 * <p>Structure is pushed with {@code appendInline}, which is what lifts the
 * ceiling on authored UIs: layouts no longer have to exist as {@code .ui} files
 * shipped in the asset pack, so an edited document renders its new shape on the
 * next open. Widgets that need Hytale's own chrome — dropdowns, text fields,
 * primary/secondary buttons — are still appended from small template files,
 * because their styles live in {@code Common.ui} and cannot be referenced from
 * an inline fragment.</p>
 */
public final class LayoutRenderer {

    static final String TEMPLATE_TEXT_INPUT = "MysticEssentials/LayoutTextInput.ui";
    static final String TEMPLATE_DROPDOWN = "MysticEssentials/LayoutDropdown.ui";
    static final String TEMPLATE_PRIMARY = "MysticEssentials/LayoutPrimaryButton.ui";
    static final String TEMPLATE_SECONDARY = "MysticEssentials/LayoutSecondaryButton.ui";
    static final String TEMPLATE_TERTIARY = "MysticEssentials/LayoutTertiaryButton.ui";

    /** Progress bars are filled by weighting two segments out of this total. */
    private static final int PROGRESS_SCALE = 1000;
    /** Cap on inputs whose values ride along with every event. */
    private static final int MAX_TRACKED_INPUTS = 16;

    private final PlayerRef player;
    private final LayoutBridge bridge;
    private final PlayerPortraitService portraits;
    private final UICommandBuilder cmd;
    private final UIEventBuilder event;
    private final RenderedLayout rendered = new RenderedLayout();
    /**
     * Bindings are collected while walking and flushed at the end, so every
     * event carries the values of all inputs — including ones declared after
     * the element being bound.
     */
    private final List<Binding> bindings = new ArrayList<>();
    /** Namespace for this surface's generated ids; see {@link UiAssets}. */
    private final String prefix;
    private int nextSelector;

    private record Binding(CustomUIEventBindingType type, String selector, String action, UiNode node,
            String nodeSelector, String valueSelector) {
    }

    private LayoutRenderer(UiDocument document, PlayerRef player, LayoutBridge bridge,
            PlayerPortraitService portraits,
            UICommandBuilder cmd, UIEventBuilder event) {
        this.prefix = UiAssets.selectorPrefix(document);
        this.player = player;
        this.bridge = bridge;
        this.portraits = portraits;
        this.cmd = cmd;
        this.event = event;
    }

    /**
     * Renders {@code document} into {@code rootSelector}.
     *
     * @param event event builder, or {@code null} for surfaces without input
     * @return the addressable result, used for events and live refreshes
     */
    public static RenderedLayout render(UiDocument document, PlayerRef player, LayoutBridge bridge,
            PlayerPortraitService portraits,
            UICommandBuilder cmd, UIEventBuilder event, String rootSelector) {
        LayoutRenderer renderer = new LayoutRenderer(document, player, bridge, portraits, cmd, event);
        UiNode root = document.root;
        renderer.renderChildren(root, rootSelector, root.style.flow(UiStyle.Flow.TOP));
        renderer.flushBindings();
        return renderer.rendered;
    }

    private void flushBindings() {
        if (event == null) {
            return;
        }
        for (Binding binding : bindings) {
            EventData data = payload(binding.action(), binding.nodeSelector());
            if (binding.valueSelector() != null) {
                data = data.append("@value", binding.valueSelector() + ".Value");
            }
            event.addEventBinding(binding.type(), binding.selector(), data);
        }
    }

    /**
     * Pushes current values for every live label and requirement-gated node.
     * Called on a timer and after actions, without touching structure.
     */
    public static void refreshValues(RenderedLayout layout, PlayerRef player, LayoutBridge bridge,
            UICommandBuilder cmd) {
        for (RenderedLayout.Live live : layout.live()) {
            if (!layout.emitted(live.selector())) {
                continue;
            }
            String raw = live.meta() ? live.node().meta : live.node().text;
            cmd.set(live.selector() + ".TextSpans",
                    uiText(live.selector() + ".TextSpans", bridge.substitute(player, raw)));
        }
        for (RenderedLayout.Gated gated : layout.gated()) {
            if (!layout.emitted(gated.selector())) {
                continue;
            }
            cmd.set(gated.selector() + ".Visible",
                    bridge.meetsRequirements(player, gated.node().requirements));
        }
    }

    /** Queues newly available portrait bytes and patches only matching image nodes. */
    public static void refreshPortraits(RenderedLayout layout, PlayerRef player,
            PlayerPortraitService portraits, String username, UICommandBuilder cmd) {
        if (layout == null || portraits == null) return;
        for (RenderedLayout.Portrait portrait : layout.portraits()) {
            if (username != null && !portrait.username().equalsIgnoreCase(username)) continue;
            String texture = portraits.queue(portrait.username(), portrait.slot(), player.getPacketHandler());
            String resolved = UiStyle.texture(texture);
            if (resolved != null) {
                cmd.setObject(portrait.selector() + ".Background", new PatchStyle(Value.of(resolved)));
            }
        }
    }

    // --------------------------------------------------------------- walking

    private void renderChildren(UiNode parent, String parentSelector, UiStyle.Flow parentFlow) {
        Integer cellWidth = gridCellWidth(parent);
        for (UiNode child : parent.children) {
            renderNode(child, parentSelector, parentFlow, parent.style.gap, cellWidth);
        }
    }

    private void renderNode(UiNode node, String parentSelector, UiStyle.Flow parentFlow,
            Integer gap, Integer cellWidth) {
        String selector = nextSelector();
        rendered.register(selector, node);

        // A requirement that fails hides the node rather than dropping it, so a
        // refresh can reveal it later without rebuilding the page.
        boolean visible = node.requirements.isEmpty() || bridge.meetsRequirements(player, node.requirements);
        if (!node.requirements.isEmpty()) {
            rendered.registerGated(node, selector);
        }

        switch (node.kind) {
            case CHROME_BUTTON ->
                    renderChromeButton(node, selector, parentSelector, parentFlow, gap, cellWidth);
            case DROPDOWN -> renderDropdown(node, selector, parentSelector, parentFlow, gap, cellWidth);
            case FIELD -> renderField(node, selector, parentSelector, parentFlow, gap, cellWidth);
            case SEARCH -> renderSearch(node, selector, parentSelector, parentFlow, gap, cellWidth);
            case PROGRESS -> renderProgress(node, selector, parentSelector, parentFlow, gap, cellWidth);
            default -> renderElement(node, selector, parentSelector, parentFlow, gap, cellWidth);
        }

        if (!visible) {
            cmd.set(selector + ".Visible", false);
        }
    }

    /**
     * @return the next element id for this render pass, namespaced to the
     *         surface so it cannot collide with another document's ids — ours
     *         or another mod's
     */
    private String nextSelector() {
        String selector = "#" + prefix + nextSelector++;
        rendered.registerEmitted(selector);
        return selector;
    }

    /** Renders the nodes that are a single inline element, plus their content. */
    private void renderElement(UiNode node, String selector, String parentSelector,
            UiStyle.Flow parentFlow, Integer gap, Integer cellWidth) {
        cmd.appendInline(parentSelector, MarkupEmitter.emit(node, selector, parentFlow, gap, cellWidth));

        switch (node.kind) {
            case TEXT, HEADING, SECTION -> setText(node, selector, false);
            case ITEM -> {
                if (!node.resource.isBlank()) {
                    cmd.set(selector + ".ItemId", node.resource);
                }
                bindActivation(node, selector, selector);
            }
            case IMAGE -> {
                if (node.resource.startsWith("portrait:") && portraits != null) {
                    String username = bridge.substitute(player, node.resource.substring("portrait:".length()));
                    String slot = prefix + "_" + selector.substring(1);
                    String texture = portraits.queue(username, slot, player.getPacketHandler());
                    rendered.registerPortrait(username, slot, selector);
                    if (texture.isBlank()) texture = node.meta;
                    applyTexture(selector, texture);
                } else {
                    applyTexture(selector, node.resource);
                }
            }
            case BUTTON, TOGGLE -> {
                applyTexture(selector, node.style.backgroundImage);
                renderInteractiveContent(node, selector);
                bindActivation(node, selector, selector);
            }
            case PANEL, CONTAINER -> {
                applyTexture(selector, node.style.backgroundImage);
                String content = node.kind == UiNode.Kind.PANEL && node.style.accent != null
                        ? renderAccentedBody(node, selector) : selector;
                renderPanelHeading(node, content);
                renderChildren(node, content, content.equals(selector)
                        ? node.style.flow(UiStyle.Flow.TOP) : UiStyle.Flow.TOP);
            }
            default -> {
                // SEPARATOR and SPACER are complete as emitted.
            }
        }
    }

    /** Emits the heading of a {@code <panel>} before its authored children. */
    private void renderPanelHeading(UiNode node, String parentSelector) {
        if (node.kind != UiNode.Kind.PANEL || node.text.isBlank()) {
            return;
        }
        UiNode heading = new UiNode(UiNode.Kind.SECTION, new UiStyle());
        heading.text = node.text;
        heading.style.marginBottom = UiTheme.DEFAULT_GAP;
        renderNode(heading, parentSelector, UiStyle.Flow.TOP, null, null);
    }

    /**
     * Splits an accented panel into its left colour bar and a content column.
     *
     * @return the selector children should be appended to
     */
    private String renderAccentedBody(UiNode node, String selector) {
        cmd.appendInline(selector,
                MarkupEmitter.accentBar(nextSelector(), node.style.accent, null));
        UiNode body = new UiNode(UiNode.Kind.CONTAINER, new UiStyle());
        body.style.flow = UiStyle.Flow.TOP;
        body.style.flex = 1;
        body.style.gap = node.style.gap;
        String bodySelector = nextSelector();
        cmd.appendInline(selector, MarkupEmitter.emit(body, bodySelector, UiStyle.Flow.LEFT, null, null));
        return bodySelector;
    }

    /**
     * Fills a card, nav button or toggle: accent bar, icon, title/subtitle
     * column, state label, then any authored children.
     */
    private void renderInteractiveContent(UiNode node, String selector) {
        UiStyle.Flow flow = node.style.flow(UiStyle.Flow.LEFT);

        if (node.style.accent != null) {
            cmd.appendInline(selector, MarkupEmitter.accentBar(
                    nextSelector(), node.style.accent, node.style.height));
        }
        if (!node.resource.isBlank()) {
            renderIcon(node, selector);
        }

        boolean hasSubtitle = !node.meta.isBlank() && node.kind != UiNode.Kind.TOGGLE;
        if (!node.text.isBlank()) {
            UiNode column = new UiNode(UiNode.Kind.CONTAINER, new UiStyle());
            column.style.flow = UiStyle.Flow.TOP;
            column.style.flex = 1;
            UiNode title = new UiNode(UiNode.Kind.TEXT, new UiStyle());
            title.text = node.text;
            title.style.fontSize = node.style.fontSize == null ? 15 : node.style.fontSize;
            title.style.textColor = node.style.textColor == null ? UiTheme.TEXT_STRONG : node.style.textColor;
            title.style.bold = true;
            title.style.wrap = false;
            column.children.add(title);
            if (hasSubtitle) {
                UiNode subtitle = new UiNode(UiNode.Kind.TEXT, new UiStyle());
                subtitle.text = node.meta;
                subtitle.style.fontSize = 12;
                subtitle.style.textColor = UiTheme.TEXT_MUTED;
                subtitle.style.wrap = false;
                column.children.add(subtitle);
            }
            renderNode(column, selector, flow, null, null);
        }

        if (node.kind == UiNode.Kind.TOGGLE) {
            boolean active = toggleActive(node, player, bridge);
            UiNode state = new UiNode(UiNode.Kind.TEXT, new UiStyle());
            state.text = active ? "ON" : "OFF";
            state.style.width = 54;
            state.style.bold = true;
            state.style.alignHorizontal = "End";
            state.style.textColor = active ? UiTheme.SUCCESS : UiTheme.TEXT_DIM;
            state.style.wrap = false;
            renderNode(state, selector, flow, null, null);
        }

        renderChildren(node, selector, flow);
    }

    /** Draws a card icon, accepting either an item id or a texture path. */
    private void renderIcon(UiNode node, String selector) {
        String iconSelector = nextSelector();
        String texture = UiStyle.texture(node.resource);
        int size = node.style.height == null ? 34 : Math.min(64, Math.max(16, node.style.height - 10));
        if (texture != null) {
            cmd.appendInline(selector, "Group " + iconSelector + " {\n  Anchor: (Width: " + size
                    + ", Height: " + size + ", Right: 10);\n}");
            applyTexture(iconSelector, texture);
        } else {
            cmd.appendInline(selector, "ItemIcon " + iconSelector + " {\n  Anchor: (Width: " + size
                    + ", Height: " + size + ", Right: 10);\n}");
            cmd.set(iconSelector + ".ItemId", node.resource);
        }
    }

    // ------------------------------------------------------------- templates

    private void renderChromeButton(UiNode node, String selector, String parentSelector,
            UiStyle.Flow parentFlow, Integer gap, Integer cellWidth) {
        cmd.appendInline(parentSelector,
                MarkupEmitter.templateHost(node, selector, parentFlow, gap, cellWidth));
        String template = switch (node.variant) {
            case "secondary" -> TEMPLATE_SECONDARY;
            case "tertiary" -> TEMPLATE_TERTIARY;
            default -> TEMPLATE_PRIMARY;
        };
        if (!appendTemplate(selector, template)) {
            return;
        }
        String button = selector + "[0]";
        rendered.registerEmitted(button);
        setText(node, button, false);
        bindActivation(node, selector, button);
    }

    private void renderDropdown(UiNode node, String selector, String parentSelector,
            UiStyle.Flow parentFlow, Integer gap, Integer cellWidth) {
        cmd.appendInline(parentSelector,
                MarkupEmitter.templateHost(node, selector, parentFlow, gap, cellWidth));
        renderInputLabel(node, selector);
        if (!appendTemplate(selector, TEMPLATE_DROPDOWN)) {
            return;
        }
        String input = selector + "[" + (node.text.isBlank() ? 0 : 1) + "]";
        rendered.registerEmitted(input);

        List<DropdownEntryInfo> entries = new ArrayList<>();
        entries.add(new DropdownEntryInfo(LocalizableString.fromString("-- Select --"), ""));
        for (UiNode.Option option : node.options) {
            entries.add(new DropdownEntryInfo(
                    LocalizableString.fromString(bridge.substitute(player, option.label())), option.value()));
        }
        cmd.set(input + ".Entries", entries);
        rendered.registerInput(inputName(node, selector), input);
        bindings.add(new Binding(CustomUIEventBindingType.ValueChanged, input, "select", node,
                selector, input));
    }

    private void renderField(UiNode node, String selector, String parentSelector,
            UiStyle.Flow parentFlow, Integer gap, Integer cellWidth) {
        cmd.appendInline(parentSelector,
                MarkupEmitter.templateHost(node, selector, parentFlow, gap, cellWidth));
        renderInputLabel(node, selector);
        if (!appendTemplate(selector, TEMPLATE_TEXT_INPUT)) {
            return;
        }
        String input = selector + "[" + (node.text.isBlank() ? 0 : 1) + "]";
        rendered.registerEmitted(input);
        if (!node.placeholder.isBlank()) {
            cmd.set(input + ".PlaceholderText", bridge.substitute(player, node.placeholder));
        }
        if (!node.meta.isBlank()) {
            cmd.set(input + ".Value", bridge.substitute(player, node.meta));
        }
        rendered.registerInput(inputName(node, selector), input);
    }

    /**
     * A search box is a field plus an explicit submit button — deliberately not
     * a {@code ValueChanged} binding, which would rebuild the page on every
     * keystroke and steal focus from the field being typed into.
     */
    private void renderSearch(UiNode node, String selector, String parentSelector,
            UiStyle.Flow parentFlow, Integer gap, Integer cellWidth) {
        cmd.appendInline(parentSelector,
                MarkupEmitter.templateHost(node, selector, parentFlow, gap, cellWidth));
        if (!appendTemplate(selector, TEMPLATE_TEXT_INPUT)) {
            return;
        }
        String input = selector + "[0]";
        rendered.registerEmitted(input);
        cmd.set(input + ".PlaceholderText", bridge.substitute(player, node.placeholder));
        rendered.registerInput(inputName(node, selector), input);

        UiNode submit = new UiNode(UiNode.Kind.BUTTON, new UiStyle());
        submit.text = node.text.isBlank() ? "Search" : node.text;
        submit.style.width = 96;
        submit.style.height = 34;
        submit.style.marginLeft = 8;
        submit.style.flow = UiStyle.Flow.CENTER;
        submit.actions = node.actions;
        submit.close = node.close;
        submit.refresh = node.refresh == null ? Boolean.TRUE : node.refresh;
        renderNode(submit, selector, UiStyle.Flow.LEFT, null, null);
    }

    private void renderProgress(UiNode node, String selector, String parentSelector,
            UiStyle.Flow parentFlow, Integer gap, Integer cellWidth) {
        if (!node.text.isBlank()) {
            UiNode label = new UiNode(UiNode.Kind.TEXT, new UiStyle());
            label.text = node.text;
            label.style.fontSize = 12;
            label.style.textColor = UiTheme.TEXT_MUTED;
            label.style.marginBottom = 4;
            label.style.wrap = false;
            renderNode(label, parentSelector, parentFlow, null, null);
        }
        int height = node.style.height == null ? 8 : node.style.height;
        cmd.appendInline(parentSelector, MarkupEmitter.progressTrack(selector, height));
        int filled = (int) Math.round(node.value * PROGRESS_SCALE);
        String color = node.style.textColor == null ? UiTheme.ACCENT : node.style.textColor;
        cmd.appendInline(selector, MarkupEmitter.progressSegment(nextSelector(), filled, color));
        cmd.appendInline(selector,
                MarkupEmitter.progressSegment(nextSelector(), PROGRESS_SCALE - filled, null));
    }

    /** Emits the caption above a dropdown or text field, when one is set. */
    private void renderInputLabel(UiNode node, String selector) {
        if (node.text.isBlank()) {
            return;
        }
        UiNode label = new UiNode(UiNode.Kind.TEXT, new UiStyle());
        label.text = node.text;
        label.style.fontSize = 12;
        label.style.textColor = UiTheme.TEXT_MUTED;
        label.style.marginBottom = 4;
        label.style.wrap = false;
        renderNode(label, selector, UiStyle.Flow.TOP, null, null);
    }

    // --------------------------------------------------------------- helpers

    private void setText(UiNode node, String selector, boolean meta) {
        String raw = meta ? node.meta : node.text;
        cmd.set(selector + ".TextSpans",
                uiText(selector + ".TextSpans", bridge.substitute(player, raw)));
        if (raw.contains("%") || raw.contains("{")) {
            // Only placeholder-bearing text can change between refreshes.
            rendered.registerLive(node, selector, meta);
        }
    }

    /**
     * Appends a shipped widget template, skipping it when the file is not in
     * the jar. Sending the append anyway would disconnect the player outright.
     */
    private boolean appendTemplate(String selector, String template) {
        if (!UiAssets.exists(template)) {
            rendered.registerProblem("missing widget template " + template);
            return false;
        }
        cmd.append(selector, template);
        return true;
    }

    private void applyTexture(String selector, String path) {
        String texture = UiStyle.texture(path);
        if (texture == null) {
            return;
        }
        PatchStyle style = new PatchStyle(Value.of(texture));
        cmd.setObject(selector + ".Background", style);
    }

    /**
     * @param nodeSelector the id the event payload reports, used to look the
     *                     node back up on activation
     * @param bound        the element the binding is attached to, which differs
     *                     from {@code nodeSelector} for template-backed widgets
     */
    private void bindActivation(UiNode node, String nodeSelector, String bound) {
        if (event == null || !node.interactive()) {
            return;
        }
        bindings.add(new Binding(CustomUIEventBindingType.Activating, bound, "activate", node,
                nodeSelector, null));
    }

    /** @return event data naming the node and carrying every input's value. */
    private EventData payload(String action, String nodeSelector) {
        EventData data = new EventData().put("action", action).put("node", nodeSelector);
        int tracked = 0;
        for (var entry : rendered.inputs().entrySet()) {
            if (tracked++ >= MAX_TRACKED_INPUTS) {
                break;
            }
            data = data.append("@in_" + entry.getKey(), entry.getValue() + ".Value");
        }
        return data;
    }

    /**
     * A toggle is on when the document marked it checked or when the player
     * satisfies its state requirements. Computed per viewer rather than stored,
     * because one compiled document is shared by everyone who opens it.
     */
    static boolean toggleActive(UiNode node, PlayerRef player, LayoutBridge bridge) {
        return node.active || (!node.stateRequirements.isEmpty()
                && bridge.meetsRequirements(player, node.stateRequirements));
    }

    private static String inputName(UiNode node, String selector) {
        return node.name.isBlank() ? selector.substring(1) : node.name;
    }

    /** @return the width each cell of a grid gets, or {@code null} when free. */
    private static Integer gridCellWidth(UiNode parent) {
        if (parent.style.columns == null || parent.style.width == null) {
            return null;
        }
        int gap = parent.style.gap == null ? 0 : parent.style.gap;
        int inner = parent.style.width
                - (parent.style.padLeft == null ? 0 : parent.style.padLeft)
                - (parent.style.padRight == null ? 0 : parent.style.padRight);
        int width = (inner - gap * (parent.style.columns - 1)) / parent.style.columns;
        return width > 0 ? width : null;
    }

    /** @return an {@link Anchor} sized for a page shell or HUD root. */
    public static Anchor anchor(Integer left, Integer top, Integer right, Integer bottom,
            Integer width, Integer height) {
        Anchor anchor = new Anchor();
        if (left != null) {
            anchor.setLeft(Value.of(left));
        }
        if (top != null) {
            anchor.setTop(Value.of(top));
        }
        if (right != null) {
            anchor.setRight(Value.of(right));
        }
        if (bottom != null) {
            anchor.setBottom(Value.of(bottom));
        }
        if (width != null) {
            anchor.setWidth(Value.of(width));
        }
        if (height != null) {
            anchor.setHeight(Value.of(height));
        }
        return anchor;
    }
}
