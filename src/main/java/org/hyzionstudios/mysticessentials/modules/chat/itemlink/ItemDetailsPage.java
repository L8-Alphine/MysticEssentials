package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.item.ItemViewData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemModifierEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemStatEntry;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.item.ItemViewConfig;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The Item Details panel: a structured, read-only view of a shared item.
 *
 * <p>Sections are generated at runtime with {@code appendInline} because which
 * sections exist depends on the item. The important consequences: important
 * information is visible without hovering anything, empty sections are absent
 * rather than blank, a modded item's own sections render alongside the built-in
 * ones, and a valid item can never open to an empty panel — identity alone is
 * still a complete, useful view.</p>
 *
 * <p>Two presentation switches live on the page rather than in config, because
 * they are the viewer's preference for this reading: {@link Mode#TOOLTIP}
 * re-renders the same data in the compact original-tooltip shape, and
 * {@code compact} narrows the shell for smaller resolutions. Collapsed sections
 * are tracked per open page, so expanding Technical Information does not persist
 * into somebody else's view.</p>
 */
public final class ItemDetailsPage extends MysticPage {

    /** Which shape the same snapshot is drawn in. */
    public enum Mode {
        /** The full sectioned layout. */
        STRUCTURED,
        /** The compact original-tooltip rendering. */
        TOOLTIP
    }

    static final String UI = "MysticEssentials/ItemDetails.ui";

    private static final int WIDTH_FULL = 820;
    private static final int WIDTH_COMPACT = 560;
    private static final int HEIGHT_FULL = 700;
    private static final int HEIGHT_COMPACT = 620;

    /** Guard so a pathological item cannot emit an unbounded number of elements. */
    private static final int MAX_EMITTED_ROWS = 400;

    private final ItemSnapshot snapshot;
    private final ItemViewConfig config;
    private final Mode mode;
    private final boolean compact;
    private final Set<String> expanded;

    /** Monotonic counter giving every generated element a unique id. */
    private int nextElementId;
    private int emittedRows;

    public ItemDetailsPage(MysticCore core, PlayerRef player, ItemSnapshot snapshot,
            ItemViewConfig config) {
        this(core, player, snapshot, config, Mode.STRUCTURED, false, Set.of());
    }

    public ItemDetailsPage(MysticCore core, PlayerRef player, ItemSnapshot snapshot,
            ItemViewConfig config, Mode mode, boolean compact, Set<String> expanded) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.snapshot = snapshot;
        this.config = config;
        this.mode = mode == null ? Mode.STRUCTURED : mode;
        this.compact = compact;
        this.expanded = expanded == null ? Set.of() : Set.copyOf(expanded);
    }

    // ----- Build ----------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
            Store<EntityStore> store) {
        cmd.append(UI);
        ItemViewData view = snapshot.view;

        applyShellSize(cmd);
        buildHeader(cmd, view);

        if (mode == Mode.TOOLTIP) {
            buildTooltipBody(cmd, view);
        } else {
            buildStructuredBody(cmd, event, view);
        }

        buildFooter(cmd, event);
    }

    private void applyShellSize(UICommandBuilder cmd) {
        try {
            com.hypixel.hytale.server.core.ui.Anchor anchor =
                    new com.hypixel.hytale.server.core.ui.Anchor();
            anchor.setWidth(com.hypixel.hytale.server.core.ui.Value.of(
                    compact ? WIDTH_COMPACT : WIDTH_FULL));
            anchor.setHeight(com.hypixel.hytale.server.core.ui.Value.of(
                    compact ? HEIGHT_COMPACT : HEIGHT_FULL));
            cmd.setObject("#MysticItemShell.Anchor", anchor);
        } catch (Throwable ignored) {
            // Runtime resizing is a nicety; the shell's authored size is a fine
            // fallback and must never cost the player the whole panel.
        }
    }

    private void buildHeader(UICommandBuilder cmd, ItemViewData view) {
        cmd.set("#HeaderTitle.TextSpans", uiText("#HeaderTitle.TextSpans",
                mode == Mode.TOOLTIP ? "Original Item Tooltip" : "Item Details"));

        cmd.set("#Icon.ItemId", view.itemId());
        cmd.set("#Icon.Quantity", Math.max(1, view.quantity()));
        cmd.set("#RarityAccent.Background", accent());

        // Only a *translation* Message may be handed to the client here; a raw
        // Message is a known 0.5.6 disconnect. So the translated case passes the
        // Message and every other case passes an already-resolved plain String.
        cmd.set("#ItemName.TextSpans", view.displayName().isTranslated()
                ? uiText("#ItemName.TextSpans", snapshot.nameMessage())
                : uiText("#ItemName.TextSpans", snapshot.plainName()));
        cmd.set("#ItemSubtitle.TextSpans",
                uiText("#ItemSubtitle.TextSpans", ItemViewSections.subtitle(view)));

        boolean showId = config.display.showItemId;
        cmd.set("#ItemId.Visible", showId);
        if (showId) {
            cmd.set("#ItemId.TextSpans", uiText("#ItemId.TextSpans", "ID: " + view.itemId()));
        }

        // The badge shows the quality's literal name. It is hidden only when the
        // item has no quality at all — never because the name looks unusual.
        String badge = ItemViewSections.qualityBadge(view, config);
        cmd.set("#QualityBadge.Visible", badge != null);
        if (badge != null) {
            cmd.set("#QualityBadgeLabel.TextSpans", uiText("#QualityBadgeLabel.TextSpans", badge));
        }
    }

    // ----- Structured body -------------------------------------------------------

    private void buildStructuredBody(UICommandBuilder cmd, UIEventBuilder event, ItemViewData view) {
        List<ItemViewSections.Section> sections =
                ItemViewSections.build(view, config, expanded);

        if (sections.isEmpty()) {
            // Identity alone is still a valid view. Say so plainly rather than
            // leaving the reader staring at an empty panel.
            emitParagraph(cmd, "This item carries no additional details.", "#9fb0c4", 13);
            return;
        }

        for (ItemViewSections.Section section : sections) {
            emitSection(cmd, event, section);
        }
    }

    private void emitSection(UICommandBuilder cmd, UIEventBuilder event,
            ItemViewSections.Section section) {
        String headerId = nextId("sh");
        String accent = safeColor(section.accentColor() == null
                ? config.fallback.neutralAccentColor : section.accentColor());

        // Header row: title on the left, an optional Show/Hide toggle on the right.
        cmd.appendInline("#MysticItemBody", "Group " + headerId + " {\n"
                + "  LayoutMode: Left;\n"
                + "  Anchor: (Height: 26, Bottom: 4, Top: 8);\n"
                + "}");

        String titleId = nextId("st");
        cmd.appendInline(headerId, "Label " + titleId + " {\n"
                + "  FlexWeight: 1;\n"
                + "  Style: (FontSize: 14, RenderBold: true, TextColor: " + accent
                + ", VerticalAlignment: Center);\n"
                + "}");
        cmd.set(titleId + ".TextSpans", uiText(titleId + ".TextSpans",
                section.title().toUpperCase(java.util.Locale.ROOT)));

        if (section.collapsible()) {
            String toggleId = nextId("sg");
            cmd.appendInline(headerId, "Button " + toggleId + " {\n"
                    + "  Anchor: (Width: 74, Height: 22);\n"
                    + "  Style: (Default: (Background: #000000(0.18)),"
                    + " Hovered: (Background: #7a9cc6(0.22)),"
                    + " Pressed: (Background: #7a9cc6(0.34)));\n"
                    + "}");
            String toggleLabelId = nextId("sl");
            cmd.appendInline(toggleId, "Label " + toggleLabelId + " {\n"
                    + "  Anchor: (Full: 2);\n"
                    + "  Style: (FontSize: 12, TextColor: #cddbe8,"
                    + " HorizontalAlignment: Center, VerticalAlignment: Center);\n"
                    + "}");
            cmd.set(toggleLabelId + ".TextSpans", uiText(toggleLabelId + ".TextSpans",
                    section.expanded() ? "Hide" : "Show"));
            event.addEventBinding(CustomUIEventBindingType.Activating, toggleId,
                    new EventData().put("action", "toggle").put("section", section.id()));
        }

        if (!section.expanded()) {
            return;
        }
        for (ItemViewSections.Row row : section.rows()) {
            emitRow(cmd, row.label(), row.value());
        }
        for (String paragraph : section.paragraphs()) {
            emitParagraph(cmd, paragraph, "#c9d6e5", 13);
        }
    }

    /** A single label/value line. A blank value renders the label full-width. */
    private void emitRow(UICommandBuilder cmd, String label, String value) {
        if (emittedRows++ > MAX_EMITTED_ROWS) {
            return;
        }
        if (value == null || value.isBlank()) {
            emitParagraph(cmd, label, "#d7e1ee", 13);
            return;
        }
        String rowId = nextId("r");
        cmd.appendInline("#MysticItemBody", "Group " + rowId + " {\n"
                + "  LayoutMode: Left;\n"
                + "  Anchor: (Height: 22, Bottom: 3);\n"
                + "  Padding: (Left: 6, Right: 6);\n"
                + "}");

        String labelId = nextId("rl");
        cmd.appendInline(rowId, "Label " + labelId + " {\n"
                + "  FlexWeight: 1;\n"
                + "  Style: (FontSize: 13, TextColor: #9fb0c4, VerticalAlignment: Center);\n"
                + "}");
        cmd.set(labelId + ".TextSpans", uiText(labelId + ".TextSpans", label));

        String valueId = nextId("rv");
        cmd.appendInline(rowId, "Label " + valueId + " {\n"
                + "  Anchor: (Width: " + (compact ? 190 : 260) + ");\n"
                + "  Style: (FontSize: 13, RenderBold: true, TextColor: #ffffff,"
                + " HorizontalAlignment: End, VerticalAlignment: Center);\n"
                + "}");
        cmd.set(valueId + ".TextSpans", uiText(valueId + ".TextSpans", value));
    }

    /** A wrapped, full-width text line. */
    private void emitParagraph(UICommandBuilder cmd, String text, String color, int fontSize) {
        if (text == null || text.isBlank() || emittedRows++ > MAX_EMITTED_ROWS) {
            return;
        }
        String id = nextId("p");
        cmd.appendInline("#MysticItemBody", "Label " + id + " {\n"
                + "  Anchor: (Height: " + paragraphHeight(text) + ", Bottom: 4);\n"
                + "  Padding: (Left: 6, Right: 6);\n"
                + "  Style: (FontSize: " + fontSize + ", TextColor: " + safeColor(color)
                + ", Wrap: true);\n"
                + "}");
        cmd.set(id + ".TextSpans", uiText(id + ".TextSpans", text));
    }

    /** Rough wrapped height so long lore is not clipped to one line. */
    private int paragraphHeight(String text) {
        int charsPerLine = compact ? 58 : 92;
        int lines = Math.max(1, (text.length() + charsPerLine - 1) / charsPerLine);
        return Math.min(140, 18 * lines);
    }

    // ----- Tooltip body -----------------------------------------------------------

    /**
     * The original-tooltip rendering: the same server-held data laid out in the
     * dense shape the native tooltip uses. It exists because the structured view
     * and the tooltip answer different questions, and a player who knows the
     * tooltip layout should not have to relearn it to read a shared item.
     */
    private void buildTooltipBody(UICommandBuilder cmd, ItemViewData view) {
        var classification = view.classification();

        classification.quality().ifPresent(quality ->
                emitParagraph(cmd, quality.displayName(), accent(), 14));
        classification.subcategory().or(classification::category).ifPresent(type ->
                emitParagraph(cmd, type, "#96a9be", 13));

        List<String> body = new ArrayList<>();
        view.description().forEach(line -> addIfPresent(body, line.plain()));
        view.lore().forEach(line -> addIfPresent(body, line.plain()));
        body.forEach(line -> emitParagraph(cmd, line, "#c9d6e5", 13));

        for (ItemModifierEntry modifier : view.modifiers()) {
            emitParagraph(cmd, modifier.display(), modifier.amount() < 0 ? "#d46a6a" : "#8fd48f", 13);
        }

        classification.combinedLabel().ifPresent(label ->
                emitParagraph(cmd, label, "#e8c97a", 13));

        if (!view.primaryStats().isEmpty()) {
            emitParagraph(cmd, "Damage Data", "#E8A93B", 13);
            for (ItemStatEntry stat : view.primaryStats()) {
                emitParagraph(cmd, "• " + stat.label().plain() + ": " + stat.value().plain(),
                        "#d7e1ee", 12);
            }
        }
        view.durability()
                .filter(durability -> durability.isDisplayable())
                .ifPresent(durability ->
                        emitParagraph(cmd, "Durability: " + durability.display(), "#9fb0c4", 12));
    }

    private static void addIfPresent(List<String> target, String line) {
        if (line != null && !line.isBlank()) {
            target.add(line);
        }
    }

    // ----- Footer ------------------------------------------------------------------

    private void buildFooter(UICommandBuilder cmd, UIEventBuilder event) {
        boolean showShare = config.sections.shareInformation;
        cmd.set("#ShareInfo.Visible", showShare);
        if (showShare) {
            cmd.set("#ShareInfo.TextSpans", uiText("#ShareInfo.TextSpans", shareInfo()));
        }

        cmd.set("#LayoutButton.TextSpans", uiText("#LayoutButton.TextSpans",
                compact ? "Full View" : "Compact"));
        boolean showTooltipButton = config.display.showOriginalTooltipButton;
        cmd.set("#TooltipButton.Visible", showTooltipButton);
        if (showTooltipButton) {
            cmd.set("#TooltipButton.TextSpans", uiText("#TooltipButton.TextSpans",
                    mode == Mode.TOOLTIP ? "Item Details" : "Original Tooltip"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#TooltipButton",
                    new EventData().put("action", "tooltip"));
        }

        event.addEventBinding(CustomUIEventBindingType.Activating, "#LayoutButton",
                new EventData().put("action", "layout"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                new EventData().put("action", "close"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ReportButton",
                new EventData().put("action", "report"));
    }

    private String shareInfo() {
        StringBuilder sb = new StringBuilder("Shared by ")
                .append(snapshot.senderName.isBlank() ? "unknown" : snapshot.senderName);
        if (!snapshot.channelName.isBlank()) {
            sb.append("  •  ").append(snapshot.channelName);
        }
        if (!snapshot.worldName.isBlank()) {
            sb.append("  •  ").append(snapshot.worldName);
        }
        sb.append("  •  ").append(relativeTime(snapshot.capturedAtEpochMs()));
        return sb.toString();
    }

    private String accent() {
        return safeColor(snapshot.accentColor().orElse(config.fallback.neutralAccentColor));
    }

    private String nextId(String prefix) {
        return "#mev" + prefix + (nextElementId++);
    }

    // ----- Events --------------------------------------------------------------------

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        switch (action) {
            case "toggle" -> {
                String section = field(payload, "section");
                Set<String> next = new LinkedHashSet<>(expanded);
                if (!next.remove(section)) {
                    next.add(section);
                }
                reopen(ref, store, new ItemDetailsPage(core, player, snapshot, config, mode,
                        compact, next));
            }
            case "layout" -> reopen(ref, store, new ItemDetailsPage(core, player, snapshot, config,
                    mode, !compact, expanded));
            case "tooltip" -> reopen(ref, store, new ItemDetailsPage(core, player, snapshot, config,
                    mode == Mode.TOOLTIP ? Mode.STRUCTURED : Mode.TOOLTIP, compact, expanded));
            case "report" -> {
                core.log(Level.INFO, "[chat] item-links: " + player.getUsername()
                        + " reported shared item '" + snapshot.itemId() + "' (id=" + snapshot.id
                        + ") shared by " + snapshot.senderName);
                core.getMessageService().send(player,
                        "&aThanks — this shared item has been reported to staff.");
                close(ref, store);
            }
            default -> close(ref, store);
        }
    }

    static String relativeTime(long epochMs) {
        long seconds = Math.max(0, Duration.between(
                Instant.ofEpochMilli(epochMs), Instant.now()).getSeconds());
        if (seconds < 60) {
            return seconds + " second" + (seconds == 1 ? "" : "s") + " ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " minute" + (minutes == 1 ? "" : "s") + " ago";
        }
        long hours = minutes / 60;
        return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
    }
}
