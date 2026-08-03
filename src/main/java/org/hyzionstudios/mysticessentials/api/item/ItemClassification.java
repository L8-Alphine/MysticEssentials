package org.hyzionstudios.mysticessentials.api.item;

/**
 * One classification value on an item — a quality, rarity, tier, or grade.
 *
 * <p><b>The central invariant of this type:</b> a classification object either
 * exists or it does not. If it exists, its {@link #displayName()} is shown
 * verbatim, whatever it happens to spell. The strings {@code "Null"},
 * {@code "None"}, {@code "Unknown"}, {@code "Undefined"}, {@code "Empty"} and
 * {@code "Void"} are ordinary names a mod may legitimately give a quality, and
 * this mod must render them as-is. Missing data is represented by a {@code null}
 * {@code ItemClassification} reference (surfaced as an empty {@link java.util.Optional}
 * on {@link ItemClassificationData}), never by inspecting the name.</p>
 *
 * <p>So the only correct absence check is:</p>
 * <pre>{@code
 * if (classification == null) { ... }        // correct
 * if ("null".equalsIgnoreCase(name)) { ... } // WRONG — destroys a valid quality
 * }</pre>
 *
 * <p>Visual treatment (colour, frame, icon) comes from the classification
 * <i>definition</i>, not from its name, so a quality named {@code Null} can
 * still be purple, gold, or anything else.</p>
 */
public final class ItemClassification {

    private final String id;
    private final String displayName;
    private final RichText formattedName;
    private final String color;
    private final String accentColor;
    private final String icon;
    private final String frameAsset;
    private final Integer sortOrder;

    private ItemClassification(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.formattedName = builder.formattedName;
        this.color = builder.color;
        this.accentColor = builder.accentColor;
        this.icon = builder.icon;
        this.frameAsset = builder.frameAsset;
        this.sortOrder = builder.sortOrder;
    }

    /**
     * A classification with only a display name — the common case for a provider
     * that knows the label but has no styling to contribute.
     *
     * @param displayName the literal name; any value is kept verbatim, including
     *                    {@code "Null"}. A {@code null} or blank argument returns
     *                    {@code null}, because <i>that</i> is genuine absence.
     */
    public static ItemClassification named(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        return builder().displayName(displayName).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Namespaced identifier (e.g. {@code example_rpg:null}), or {@code null}. */
    public String id() {
        return id;
    }

    /**
     * The literal display name, rendered verbatim. Never inspect this string to
     * decide whether the classification exists.
     */
    public String displayName() {
        return displayName;
    }

    /** The styled name if the owner supplied one, else a plain wrapper of {@link #displayName()}. */
    public RichText formattedName() {
        if (formattedName != null && !formattedName.isEmpty()) {
            return formattedName;
        }
        return RichText.plain(displayName == null ? "" : displayName);
    }

    /** Primary {@code #RRGGBB} colour, or {@code null} to let the UI pick a default. */
    public String color() {
        return color;
    }

    /** Secondary {@code #RRGGBB} accent, or {@code null}. */
    public String accentColor() {
        return accentColor;
    }

    /** Icon asset path, or {@code null}. */
    public String icon() {
        return icon;
    }

    /** Frame/border asset path for the item icon, or {@code null}. */
    public String frameAsset() {
        return frameAsset;
    }

    /** Ordering hint among sibling classifications, or {@code null} when unordered. */
    public Integer sortOrder() {
        return sortOrder;
    }

    @Override
    public String toString() {
        return "ItemClassification[" + (id == null ? "" : id + " ") + displayName + "]";
    }

    public static final class Builder {
        private String id;
        private String displayName;
        private RichText formattedName;
        private String color;
        private String accentColor;
        private String icon;
        private String frameAsset;
        private Integer sortOrder;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = blankToNull(id);
            return this;
        }

        /** Sets the literal name. Kept exactly as given — {@code "Null"} stays {@code "Null"}. */
        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder formattedName(RichText formattedName) {
            this.formattedName = formattedName;
            return this;
        }

        public Builder color(String color) {
            this.color = blankToNull(color);
            return this;
        }

        public Builder accentColor(String accentColor) {
            this.accentColor = blankToNull(accentColor);
            return this;
        }

        public Builder icon(String icon) {
            this.icon = blankToNull(icon);
            return this;
        }

        public Builder frameAsset(String frameAsset) {
            this.frameAsset = blankToNull(frameAsset);
            return this;
        }

        public Builder sortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
            return this;
        }

        /**
         * Builds the classification. If no display name was set, the id's local
         * part is prettified into one, so a provider that only knows
         * {@code example_rpg:maelstrom} still yields a showable {@code Maelstrom}.
         */
        public ItemClassification build() {
            if (displayName == null || displayName.isBlank()) {
                displayName = ItemNames.prettify(localPart(id));
            }
            return new ItemClassification(this);
        }

        private static String localPart(String id) {
            if (id == null) {
                return "";
            }
            int colon = id.indexOf(':');
            return colon >= 0 && colon + 1 < id.length() ? id.substring(colon + 1) : id;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
