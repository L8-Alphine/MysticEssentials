package org.hyzionstudios.mysticessentials.api.item;

import java.util.Optional;

/**
 * The four classification axes an item may declare, kept strictly separate.
 *
 * <p>Quality, rarity, tier, and grade mean different things to different mods
 * and must never be conflated. In particular, a tooltip line reading
 * {@code "Maelstrom — Epic"} is <b>not</b> evidence that {@code Maelstrom} is a
 * tier and {@code Epic} is a rarity; unless a provider or structured metadata
 * says so, that whole string belongs in {@link #combinedLabel()} and is shown as
 * a single mod-provided classification.</p>
 *
 * <p>Every axis returns an {@link Optional}: empty means the item genuinely has
 * no value on that axis. A present value is displayed exactly as named, so a
 * quality literally called {@code Null} renders as {@code Null}.</p>
 */
public final class ItemClassificationData {

    private static final ItemClassificationData EMPTY = builder().build();

    private final ItemClassification quality;
    private final ItemClassification rarity;
    private final ItemClassification tier;
    private final ItemClassification grade;

    private final String category;
    private final String subcategory;
    private final String equipmentSlot;
    private final String combinedLabel;

    private ItemClassificationData(Builder builder) {
        this.quality = builder.quality;
        this.rarity = builder.rarity;
        this.tier = builder.tier;
        this.grade = builder.grade;
        this.category = builder.category;
        this.subcategory = builder.subcategory;
        this.equipmentSlot = builder.equipmentSlot;
        this.combinedLabel = builder.combinedLabel;
    }

    public static ItemClassificationData empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The item's quality, or empty when the item declares none. */
    public Optional<ItemClassification> quality() {
        return Optional.ofNullable(quality);
    }

    public Optional<ItemClassification> rarity() {
        return Optional.ofNullable(rarity);
    }

    public Optional<ItemClassification> tier() {
        return Optional.ofNullable(tier);
    }

    public Optional<ItemClassification> grade() {
        return Optional.ofNullable(grade);
    }

    public Optional<String> category() {
        return Optional.ofNullable(category);
    }

    public Optional<String> subcategory() {
        return Optional.ofNullable(subcategory);
    }

    public Optional<String> equipmentSlot() {
        return Optional.ofNullable(equipmentSlot);
    }

    /**
     * An uninterpreted classification string the owning mod supplied as one unit
     * (e.g. {@code "Maelstrom — Epic"}). Present only when the meaning of its
     * parts is unknown; the UI shows it on a single {@code Classification} line
     * rather than guessing which half is a tier and which a rarity.
     */
    public Optional<String> combinedLabel() {
        return Optional.ofNullable(combinedLabel);
    }

    /** Whether any axis or category field carries a value. */
    public boolean isEmpty() {
        return quality == null && rarity == null && tier == null && grade == null
                && category == null && subcategory == null && equipmentSlot == null
                && combinedLabel == null;
    }

    /** A copy of this data with every non-null field of {@code overlay} applied on top. */
    public ItemClassificationData mergedWith(ItemClassificationData overlay) {
        if (overlay == null || overlay.isEmpty()) {
            return this;
        }
        return builder()
                .quality(overlay.quality != null ? overlay.quality : quality)
                .rarity(overlay.rarity != null ? overlay.rarity : rarity)
                .tier(overlay.tier != null ? overlay.tier : tier)
                .grade(overlay.grade != null ? overlay.grade : grade)
                .category(overlay.category != null ? overlay.category : category)
                .subcategory(overlay.subcategory != null ? overlay.subcategory : subcategory)
                .equipmentSlot(overlay.equipmentSlot != null ? overlay.equipmentSlot : equipmentSlot)
                .combinedLabel(overlay.combinedLabel != null ? overlay.combinedLabel : combinedLabel)
                .build();
    }

    public Builder toBuilder() {
        return builder()
                .quality(quality)
                .rarity(rarity)
                .tier(tier)
                .grade(grade)
                .category(category)
                .subcategory(subcategory)
                .equipmentSlot(equipmentSlot)
                .combinedLabel(combinedLabel);
    }

    public static final class Builder {
        private ItemClassification quality;
        private ItemClassification rarity;
        private ItemClassification tier;
        private ItemClassification grade;
        private String category;
        private String subcategory;
        private String equipmentSlot;
        private String combinedLabel;

        private Builder() {
        }

        /**
         * Sets the quality. Passing {@code null} clears it — that is the only way
         * to express "this item has no quality". Passing a classification named
         * {@code "Null"} sets a real quality whose name is {@code Null}.
         */
        public Builder quality(ItemClassification quality) {
            this.quality = quality;
            return this;
        }

        public Builder rarity(ItemClassification rarity) {
            this.rarity = rarity;
            return this;
        }

        public Builder tier(ItemClassification tier) {
            this.tier = tier;
            return this;
        }

        public Builder grade(ItemClassification grade) {
            this.grade = grade;
            return this;
        }

        public Builder category(String category) {
            this.category = blankToNull(category);
            return this;
        }

        public Builder subcategory(String subcategory) {
            this.subcategory = blankToNull(subcategory);
            return this;
        }

        public Builder equipmentSlot(String equipmentSlot) {
            this.equipmentSlot = blankToNull(equipmentSlot);
            return this;
        }

        public Builder combinedLabel(String combinedLabel) {
            this.combinedLabel = blankToNull(combinedLabel);
            return this;
        }

        public ItemClassificationData build() {
            return new ItemClassificationData(this);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
