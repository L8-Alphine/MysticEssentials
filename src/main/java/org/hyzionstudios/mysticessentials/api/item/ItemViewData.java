package org.hyzionstudios.mysticessentials.api.item;

import java.util.List;
import java.util.Optional;

import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemBindingData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemDurabilityData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemMetadataEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemModifierEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemPropertyEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemRequirementEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemSourceData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemStatEntry;

/**
 * The normalized, immutable result of inspecting an item — everything the
 * ItemView UI, the chat item link, and the API expose about one item.
 *
 * <p>This model is <b>pure data</b>: it holds no UI components, no live
 * {@code ItemStack}, and no engine handles. That is what makes it safe to store
 * in a snapshot, hand to another mod, or render long after the original item
 * changed or was destroyed.</p>
 *
 * <p>Optional-returning accessors mean exactly what they say: an empty
 * {@link Optional} is data the item does not have. A present value is rendered
 * verbatim — including a quality whose name is {@code Null}.</p>
 */
public final class ItemViewData {

    private final String itemId;
    private final String namespace;
    private final RichText displayName;
    private final String iconAsset;
    private final int quantity;
    private final int stackLimit;

    private final ItemClassificationData classification;

    private final List<RichText> description;
    private final List<RichText> lore;

    private final List<ItemStatEntry> primaryStats;
    private final List<ItemStatEntry> secondaryStats;
    private final List<ItemModifierEntry> modifiers;
    private final List<ItemRequirementEntry> requirements;
    private final List<ItemPropertyEntry> properties;
    private final List<ItemViewSection> customSections;
    private final List<ItemMetadataEntry> technicalMetadata;
    private final List<String> tags;

    private final ItemDurabilityData durability;
    private final ItemBindingData binding;
    private final ItemSourceData source;

    private final Integer itemLevel;
    private final Integer requiredLevel;

    ItemViewData(ItemViewBuilder builder) {
        this.itemId = builder.itemId == null ? "" : builder.itemId;
        this.namespace = builder.namespace;
        this.displayName = builder.displayName == null ? RichText.empty() : builder.displayName;
        this.iconAsset = builder.iconAsset;
        this.quantity = Math.max(1, builder.quantity);
        this.stackLimit = builder.stackLimit;
        this.classification = builder.classification == null
                ? ItemClassificationData.empty() : builder.classification;
        this.description = List.copyOf(builder.description);
        this.lore = List.copyOf(builder.lore);
        this.primaryStats = List.copyOf(builder.primaryStats);
        this.secondaryStats = List.copyOf(builder.secondaryStats);
        this.modifiers = List.copyOf(builder.modifiers);
        this.requirements = List.copyOf(builder.requirements);
        this.properties = List.copyOf(builder.properties);
        this.customSections = List.copyOf(builder.customSections);
        this.technicalMetadata = List.copyOf(builder.technicalMetadata);
        this.tags = List.copyOf(builder.tags);
        this.durability = builder.durability;
        this.binding = builder.binding;
        this.source = builder.source;
        this.itemLevel = builder.itemLevel;
        this.requiredLevel = builder.requiredLevel;
    }

    /** The engine item id, e.g. {@code custom_mod:scarlet_requiem}. Never {@code null}. */
    public String itemId() {
        return itemId;
    }

    public Optional<String> namespace() {
        return Optional.ofNullable(namespace);
    }

    /**
     * The item's name. May be a translated segment (resolved client-side) — call
     * {@link #plainName()} when a guaranteed server-side string is needed.
     */
    public RichText displayName() {
        return displayName;
    }

    /**
     * A server-side readable name: the display name's plain form when it has one,
     * otherwise the prettified item id. Used for chat fallbacks and log lines.
     */
    public String plainName() {
        String plain = displayName.plain();
        if (!plain.isBlank()) {
            return plain;
        }
        String prettified = ItemNames.prettify(itemId);
        return prettified.isBlank() ? "Unknown Item" : prettified;
    }

    public Optional<String> iconAsset() {
        return Optional.ofNullable(iconAsset);
    }

    public int quantity() {
        return quantity;
    }

    /** Maximum stack size, or empty when the item does not declare one. */
    public Optional<Integer> stackLimit() {
        return stackLimit > 0 ? Optional.of(stackLimit) : Optional.empty();
    }

    public ItemClassificationData classification() {
        return classification;
    }

    /** Short descriptive lines shown under the item name. */
    public List<RichText> description() {
        return description;
    }

    /** Flavour text shown in the description block, below {@link #description()}. */
    public List<RichText> lore() {
        return lore;
    }

    public List<ItemStatEntry> primaryStats() {
        return primaryStats;
    }

    public List<ItemStatEntry> secondaryStats() {
        return secondaryStats;
    }

    public List<ItemModifierEntry> modifiers() {
        return modifiers;
    }

    public List<ItemRequirementEntry> requirements() {
        return requirements;
    }

    public List<ItemPropertyEntry> properties() {
        return properties;
    }

    public List<ItemViewSection> customSections() {
        return customSections;
    }

    public List<ItemMetadataEntry> technicalMetadata() {
        return technicalMetadata;
    }

    public List<String> tags() {
        return tags;
    }

    public Optional<ItemDurabilityData> durability() {
        return Optional.ofNullable(durability);
    }

    public Optional<ItemBindingData> binding() {
        return Optional.ofNullable(binding);
    }

    public Optional<ItemSourceData> source() {
        return Optional.ofNullable(source);
    }

    public Optional<Integer> itemLevel() {
        return Optional.ofNullable(itemLevel);
    }

    public Optional<Integer> requiredLevel() {
        return Optional.ofNullable(requiredLevel);
    }

    /**
     * The item's rendering accent — the quality's colour, else the rarity's, else
     * the tier's, else the grade's. Empty when no classification supplies one, in
     * which case the UI uses its neutral default.
     *
     * <p>The colour comes from the classification <i>definition</i>, never from
     * its name, so a quality named {@code Null} keeps whatever colour its owner
     * gave it.</p>
     */
    public Optional<String> accentColor() {
        for (Optional<ItemClassification> axis : List.of(
                classification.quality(), classification.rarity(),
                classification.tier(), classification.grade())) {
            Optional<String> color = axis.map(ItemClassification::color).filter(c -> c != null);
            if (color.isPresent()) {
                return color;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether this view has anything beyond the bare identity fields. A
     * {@code false} result still renders — identity alone is a valid, useful
     * ItemView, and no valid item may open to a blank screen.
     */
    public boolean hasDetail() {
        return !classification.isEmpty() || !primaryStats.isEmpty() || !secondaryStats.isEmpty()
                || !modifiers.isEmpty() || !requirements.isEmpty() || !properties.isEmpty()
                || !customSections.isEmpty() || !description.isEmpty() || !lore.isEmpty();
    }

    public static ItemViewBuilder builder(String itemId) {
        return new ItemViewBuilder(itemId);
    }

    /** A builder pre-populated with this view's contents, for layered edits. */
    public ItemViewBuilder toBuilder() {
        ItemViewBuilder builder = new ItemViewBuilder(itemId);
        builder.namespace = namespace;
        builder.displayName = displayName;
        builder.iconAsset = iconAsset;
        builder.quantity = quantity;
        builder.stackLimit = stackLimit;
        builder.classification = classification;
        builder.description.addAll(description);
        builder.lore.addAll(lore);
        builder.primaryStats.addAll(primaryStats);
        builder.secondaryStats.addAll(secondaryStats);
        builder.modifiers.addAll(modifiers);
        builder.requirements.addAll(requirements);
        builder.properties.addAll(properties);
        builder.customSections.addAll(customSections);
        builder.technicalMetadata.addAll(technicalMetadata);
        builder.tags.addAll(tags);
        builder.durability = durability;
        builder.binding = binding;
        builder.source = source;
        builder.itemLevel = itemLevel;
        builder.requiredLevel = requiredLevel;
        return builder;
    }

    @Override
    public String toString() {
        return "ItemViewData[" + itemId + " x" + quantity + "]";
    }
}
