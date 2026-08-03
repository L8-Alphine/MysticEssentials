package org.hyzionstudios.mysticessentials.api.item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemBindingData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemDurabilityData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemMetadataEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemModifierEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemPropertyEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemRequirementEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemSourceData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemStatEntry;

/**
 * The mutable accumulator an {@link ItemViewProvider} writes into.
 *
 * <p><b>Layering rules.</b> The generic native inspection runs first and fills
 * the builder with everything the engine knows; providers then run in ascending
 * priority, so the highest-priority provider has the last word. Scalar setters
 * <i>overwrite</i>, which is how a provider corrects a generic value it knows
 * better. Collection methods <i>append</i>, so a provider adds to what came
 * before without having to re-state it; the explicit {@code replace…} methods
 * exist for the rarer case where a provider owns a whole list outright.</p>
 *
 * <p><b>Absence.</b> Passing {@code null} to a scalar setter is a no-op, not a
 * clear — a provider that simply does not know a field must not wipe what an
 * earlier stage established. Use the {@code clear…} methods to state absence
 * deliberately.</p>
 *
 * <p><b>Caps.</b> Every collection is bounded. A provider that emits an unbounded
 * list is silently truncated rather than allowed to produce an item view too
 * large to render or to store in a snapshot.</p>
 */
public final class ItemViewBuilder {

    /** Per-list ceiling. Generous for real items, fatal to a runaway provider. */
    public static final int MAX_ENTRIES_PER_LIST = 64;
    /** Ceiling on mod-contributed sections. */
    public static final int MAX_SECTIONS = 12;
    /** Ceiling on the length of any single label or value. */
    public static final int MAX_TEXT_LENGTH = 256;

    String itemId;
    String namespace;
    RichText displayName;
    String iconAsset;
    int quantity = 1;
    int stackLimit;

    ItemClassificationData classification = ItemClassificationData.empty();

    final List<RichText> description = new ArrayList<>();
    final List<RichText> lore = new ArrayList<>();
    final List<ItemStatEntry> primaryStats = new ArrayList<>();
    final List<ItemStatEntry> secondaryStats = new ArrayList<>();
    final List<ItemModifierEntry> modifiers = new ArrayList<>();
    final List<ItemRequirementEntry> requirements = new ArrayList<>();
    final List<ItemPropertyEntry> properties = new ArrayList<>();
    final List<ItemViewSection> customSections = new ArrayList<>();
    final List<ItemMetadataEntry> technicalMetadata = new ArrayList<>();
    final Set<String> tags = new LinkedHashSet<>();

    ItemDurabilityData durability;
    ItemBindingData binding;
    ItemSourceData source;
    Integer itemLevel;
    Integer requiredLevel;

    ItemViewBuilder(String itemId) {
        this.itemId = itemId;
        this.namespace = ItemNames.namespaceOf(itemId);
    }

    // ----- Identity -------------------------------------------------------------

    public ItemViewBuilder itemId(String itemId) {
        if (itemId != null && !itemId.isBlank()) {
            this.itemId = itemId;
            if (this.namespace == null) {
                this.namespace = ItemNames.namespaceOf(itemId);
            }
        }
        return this;
    }

    public ItemViewBuilder namespace(String namespace) {
        if (namespace != null && !namespace.isBlank()) {
            this.namespace = namespace;
        }
        return this;
    }

    public ItemViewBuilder displayName(RichText displayName) {
        if (displayName != null && !displayName.isEmpty()) {
            this.displayName = displayName;
        }
        return this;
    }

    /** Sets the display name from a literal; markup in {@code name} is escaped. */
    public ItemViewBuilder displayName(String name) {
        return displayName(RichText.plain(truncate(name)));
    }

    public ItemViewBuilder iconAsset(String iconAsset) {
        if (iconAsset != null && !iconAsset.isBlank()) {
            this.iconAsset = iconAsset;
        }
        return this;
    }

    public ItemViewBuilder quantity(int quantity) {
        if (quantity > 0) {
            this.quantity = quantity;
        }
        return this;
    }

    public ItemViewBuilder stackLimit(int stackLimit) {
        if (stackLimit > 0) {
            this.stackLimit = stackLimit;
        }
        return this;
    }

    public ItemViewBuilder itemLevel(Integer itemLevel) {
        if (itemLevel != null) {
            this.itemLevel = itemLevel;
        }
        return this;
    }

    public ItemViewBuilder requiredLevel(Integer requiredLevel) {
        if (requiredLevel != null) {
            this.requiredLevel = requiredLevel;
        }
        return this;
    }

    // ----- Classification -------------------------------------------------------

    /**
     * Sets the quality from a classification object. Passing {@code null} is a
     * no-op; call {@link #clearQuality()} to state that the item has none.
     *
     * <p>The name is taken verbatim, so {@code ItemClassification.named("Null")}
     * yields a quality that displays as {@code Null}.</p>
     */
    public ItemViewBuilder quality(ItemClassification quality) {
        if (quality != null) {
            classification = classification.toBuilder().quality(quality).build();
        }
        return this;
    }

    /** Sets the quality by id, e.g. {@code example_rpg:null} &rarr; displays {@code Null}. */
    public ItemViewBuilder quality(String id) {
        return quality(fromId(id));
    }

    /** Declares that this item has no quality at all. */
    public ItemViewBuilder clearQuality() {
        classification = classification.toBuilder().quality(null).build();
        return this;
    }

    public ItemViewBuilder rarity(ItemClassification rarity) {
        if (rarity != null) {
            classification = classification.toBuilder().rarity(rarity).build();
        }
        return this;
    }

    public ItemViewBuilder rarity(String id) {
        return rarity(fromId(id));
    }

    public ItemViewBuilder clearRarity() {
        classification = classification.toBuilder().rarity(null).build();
        return this;
    }

    public ItemViewBuilder tier(ItemClassification tier) {
        if (tier != null) {
            classification = classification.toBuilder().tier(tier).build();
        }
        return this;
    }

    public ItemViewBuilder tier(String id) {
        return tier(fromId(id));
    }

    public ItemViewBuilder clearTier() {
        classification = classification.toBuilder().tier(null).build();
        return this;
    }

    public ItemViewBuilder grade(ItemClassification grade) {
        if (grade != null) {
            classification = classification.toBuilder().grade(grade).build();
        }
        return this;
    }

    public ItemViewBuilder grade(String id) {
        return grade(fromId(id));
    }

    public ItemViewBuilder clearGrade() {
        classification = classification.toBuilder().grade(null).build();
        return this;
    }

    public ItemViewBuilder category(String category) {
        if (category != null && !category.isBlank()) {
            classification = classification.toBuilder().category(truncate(category)).build();
        }
        return this;
    }

    public ItemViewBuilder subcategory(String subcategory) {
        if (subcategory != null && !subcategory.isBlank()) {
            classification = classification.toBuilder().subcategory(truncate(subcategory)).build();
        }
        return this;
    }

    public ItemViewBuilder equipmentSlot(String equipmentSlot) {
        if (equipmentSlot != null && !equipmentSlot.isBlank()) {
            classification = classification.toBuilder().equipmentSlot(truncate(equipmentSlot)).build();
        }
        return this;
    }

    /**
     * Records a classification string whose internal structure is unknown, such
     * as {@code "Maelstrom — Epic"} lifted from a tooltip. It is shown on one
     * {@code Classification} line; the renderer will not guess that
     * {@code Maelstrom} is a tier or {@code Epic} a rarity.
     */
    public ItemViewBuilder combinedClassification(String label) {
        if (label != null && !label.isBlank()) {
            classification = classification.toBuilder().combinedLabel(truncate(label)).build();
        }
        return this;
    }

    public ItemViewBuilder classification(ItemClassificationData data) {
        if (data != null) {
            classification = classification.mergedWith(data);
        }
        return this;
    }

    // ----- Text -----------------------------------------------------------------

    public ItemViewBuilder description(String line) {
        return description(RichText.plain(truncate(line)));
    }

    public ItemViewBuilder description(RichText line) {
        addCapped(description, line, MAX_ENTRIES_PER_LIST);
        return this;
    }

    public ItemViewBuilder lore(String line) {
        return lore(RichText.plain(truncate(line)));
    }

    public ItemViewBuilder lore(RichText line) {
        addCapped(lore, line, MAX_ENTRIES_PER_LIST);
        return this;
    }

    // ----- Statistics and modifiers ---------------------------------------------

    public ItemViewBuilder addPrimaryStat(String label, String value) {
        addCapped(primaryStats, ItemStatEntry.of(truncate(label), truncate(value)), MAX_ENTRIES_PER_LIST);
        return this;
    }

    public ItemViewBuilder addPrimaryStat(String label, double value) {
        return addPrimaryStat(label, ItemNames.number(value));
    }

    public ItemViewBuilder addSecondaryStat(String label, String value) {
        addCapped(secondaryStats, ItemStatEntry.of(truncate(label), truncate(value)), MAX_ENTRIES_PER_LIST);
        return this;
    }

    public ItemViewBuilder addSecondaryStat(String label, double value) {
        return addSecondaryStat(label, ItemNames.number(value));
    }

    /** Adds a signed modifier, e.g. {@code addModifier("haste", 8.1)} &rarr; {@code +8.1 Haste}. */
    public ItemViewBuilder addModifier(String name, double amount) {
        return addModifier(name, amount, false, null);
    }

    public ItemViewBuilder addModifier(String name, double amount, boolean percentage, String source) {
        if (name != null && !name.isBlank()) {
            addCapped(modifiers, new ItemModifierEntry(
                    RichText.plain(truncate(ItemNames.prettify(name))), amount, percentage, source),
                    MAX_ENTRIES_PER_LIST);
        }
        return this;
    }

    public ItemViewBuilder addRequirement(String label, String value) {
        addCapped(requirements, ItemRequirementEntry.of(truncate(label), truncate(value)),
                MAX_ENTRIES_PER_LIST);
        return this;
    }

    public ItemViewBuilder addRequirement(ItemRequirementEntry requirement) {
        addCapped(requirements, requirement, MAX_ENTRIES_PER_LIST);
        return this;
    }

    public ItemViewBuilder addProperty(String label, String value) {
        addCapped(properties, ItemPropertyEntry.of(truncate(label), truncate(value)),
                MAX_ENTRIES_PER_LIST);
        return this;
    }

    public ItemViewBuilder addSection(ItemViewSection section) {
        if (section != null && !section.isEmpty()) {
            addCapped(customSections, section, MAX_SECTIONS);
        }
        return this;
    }

    public ItemViewBuilder addTechnical(String key, Object value) {
        addCapped(technicalMetadata, ItemMetadataEntry.of(truncate(key),
                truncate(value == null ? "" : String.valueOf(value))), MAX_ENTRIES_PER_LIST);
        return this;
    }

    public ItemViewBuilder addTag(String tag) {
        if (tag != null && !tag.isBlank() && tags.size() < MAX_ENTRIES_PER_LIST) {
            tags.add(truncate(tag));
        }
        return this;
    }

    // ----- Whole-list replacement ------------------------------------------------

    /** Replaces every modifier gathered so far — for a provider that owns the affix list. */
    public ItemViewBuilder replaceModifiers(List<ItemModifierEntry> replacement) {
        modifiers.clear();
        if (replacement != null) {
            replacement.forEach(entry -> addCapped(modifiers, entry, MAX_ENTRIES_PER_LIST));
        }
        return this;
    }

    /**
     * Drops every technical-metadata row. Used when a view must be shrunk to fit
     * a storage budget: the raw diagnostic dump is the only part that can grow
     * without bound, so it is the only part worth sacrificing.
     */
    public ItemViewBuilder clearTechnical() {
        technicalMetadata.clear();
        return this;
    }

    /** Replaces the primary statistics table. */
    public ItemViewBuilder replacePrimaryStats(List<ItemStatEntry> replacement) {
        primaryStats.clear();
        if (replacement != null) {
            replacement.forEach(entry -> addCapped(primaryStats, entry, MAX_ENTRIES_PER_LIST));
        }
        return this;
    }

    // ----- State blocks ----------------------------------------------------------

    public ItemViewBuilder durability(ItemDurabilityData durability) {
        if (durability != null) {
            this.durability = durability;
        }
        return this;
    }

    public ItemViewBuilder binding(ItemBindingData binding) {
        if (binding != null) {
            this.binding = binding;
        }
        return this;
    }

    public ItemViewBuilder source(ItemSourceData source) {
        if (source != null) {
            this.source = source;
        }
        return this;
    }

    public ItemViewData build() {
        return new ItemViewData(this);
    }

    // ----- Helpers ---------------------------------------------------------------

    /**
     * Parses {@code namespace:local} into a classification. The local part becomes
     * the display name via {@link ItemNames#prettify(String)}, so
     * {@code example_rpg:null} produces the name {@code Null} — a real value, not
     * an absent one.
     */
    private static ItemClassification fromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return ItemClassification.builder().id(id).build();
    }

    private static <T> void addCapped(List<T> target, T value, int cap) {
        if (value != null && target.size() < cap) {
            target.add(value);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH);
    }
}
