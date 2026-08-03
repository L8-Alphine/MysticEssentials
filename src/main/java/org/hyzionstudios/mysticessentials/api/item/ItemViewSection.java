package org.hyzionstudios.mysticessentials.api.item;

import java.util.ArrayList;
import java.util.List;

import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemPropertyEntry;

/**
 * A mod-defined block of rows in the ItemView, such as {@code RESONANCE} or
 * {@code SET BONUSES}.
 *
 * <p>Providers contribute <b>content</b>, not layout: a section carries a title,
 * its rows, and a {@link Placement} <i>hint</i>. Mystic Essentials decides where
 * the section actually lands, so a third-party mod can never push its block above
 * the item identity panel, interleave itself into the statistics table, or
 * otherwise break the layout other mods rely on.</p>
 */
public final class ItemViewSection {

    /**
     * Where a provider would like its section to appear. The renderer treats this
     * as a preference and clamps it into the fixed section order; unknown or
     * omitted placements fall to {@link #CUSTOM_MECHANICS}, the neutral slot
     * reserved for mod content.
     */
    public enum Placement {
        /** Directly under the classification block, above primary statistics. */
        AFTER_CLASSIFICATION,
        /** Between the statistics tables and the modifier list. */
        AFTER_STATISTICS,
        /** After modifiers — the default home for mod mechanics (resonance, sets). */
        CUSTOM_MECHANICS,
        /** Alongside requirements and restrictions. */
        WITH_REQUIREMENTS,
        /** After the description and lore block. */
        AFTER_DESCRIPTION,
        /** Inside the collapsed technical block, for diagnostic detail. */
        TECHNICAL
    }

    private final String id;
    private final RichText title;
    private final List<ItemPropertyEntry> rows;
    private final List<RichText> paragraphs;
    private final Placement placement;
    private final boolean collapsedByDefault;
    private final String accentColor;

    private ItemViewSection(Builder builder) {
        this.id = builder.id;
        this.title = builder.title;
        this.rows = List.copyOf(builder.rows);
        this.paragraphs = List.copyOf(builder.paragraphs);
        this.placement = builder.placement == null ? Placement.CUSTOM_MECHANICS : builder.placement;
        this.collapsedByDefault = builder.collapsedByDefault;
        this.accentColor = builder.accentColor;
    }

    public static Builder builder(String id, String title) {
        return new Builder(id, title);
    }

    /** Stable identifier, namespaced by the contributing provider. */
    public String id() {
        return id;
    }

    public RichText title() {
        return title;
    }

    /** Label/value rows, e.g. {@code Storm} / {@code 2 / 5 pieces}. */
    public List<ItemPropertyEntry> rows() {
        return rows;
    }

    /** Full-width text lines rendered under the rows. */
    public List<RichText> paragraphs() {
        return paragraphs;
    }

    public Placement placement() {
        return placement;
    }

    public boolean collapsedByDefault() {
        return collapsedByDefault;
    }

    /** Optional {@code #RRGGBB} accent for the section header, or {@code null}. */
    public String accentColor() {
        return accentColor;
    }

    /** A section with no rows and no paragraphs is dropped rather than rendered empty. */
    public boolean isEmpty() {
        return rows.isEmpty() && paragraphs.isEmpty();
    }

    public static final class Builder {
        private final String id;
        private final RichText title;
        private final List<ItemPropertyEntry> rows = new ArrayList<>();
        private final List<RichText> paragraphs = new ArrayList<>();
        private Placement placement = Placement.CUSTOM_MECHANICS;
        private boolean collapsedByDefault;
        private String accentColor;

        private Builder(String id, String title) {
            this.id = id == null || id.isBlank() ? "section" : id;
            this.title = RichText.plain(title == null ? "" : title);
        }

        public Builder row(String label, String value) {
            rows.add(ItemPropertyEntry.of(label, value));
            return this;
        }

        public Builder row(ItemPropertyEntry row) {
            if (row != null) {
                rows.add(row);
            }
            return this;
        }

        public Builder paragraph(String text) {
            if (text != null && !text.isBlank()) {
                paragraphs.add(RichText.plain(text));
            }
            return this;
        }

        public Builder paragraph(RichText text) {
            if (text != null && !text.isEmpty()) {
                paragraphs.add(text);
            }
            return this;
        }

        /** Suggests placement; the renderer may clamp it. */
        public Builder placement(Placement placement) {
            this.placement = placement;
            return this;
        }

        public Builder collapsedByDefault(boolean collapsedByDefault) {
            this.collapsedByDefault = collapsedByDefault;
            return this;
        }

        public Builder accentColor(String accentColor) {
            this.accentColor = accentColor == null || accentColor.isBlank() ? null : accentColor;
            return this;
        }

        public ItemViewSection build() {
            return new ItemViewSection(this);
        }
    }
}
