package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.hyzionstudios.mysticessentials.api.item.ItemClassification;
import org.hyzionstudios.mysticessentials.api.item.ItemNames;
import org.hyzionstudios.mysticessentials.api.item.ItemViewData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemBindingData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemDurabilityData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemMetadataEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemModifierEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemPropertyEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemRequirementEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemSourceData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemStatEntry;
import org.hyzionstudios.mysticessentials.api.item.ItemViewSection;
import org.hyzionstudios.mysticessentials.core.item.ItemViewConfig;

/**
 * Turns an {@link ItemViewData} into the ordered list of sections the ItemView
 * panel should draw — pure layout logic with no UI calls, so the ordering and
 * the omission rules can be reasoned about (and changed) in one place.
 *
 * <p>Two rules govern everything here.</p>
 *
 * <p><b>Empty sections do not exist.</b> A section with no rows and no
 * paragraphs is never emitted, so an item with no resonance shows no Resonance
 * header, and an unbreakable item shows no durability rather than {@code 0 / 0}.
 * That is what keeps the panel dense for a simple item and complete for a
 * complex one.</p>
 *
 * <p><b>Providers suggest, this class decides.</b> A mod-contributed section
 * carries a placement hint; it is clamped into the fixed order below, so no
 * third-party section can push itself above the identity panel or split the
 * statistics table.</p>
 */
final class ItemViewSections {

    /** Section ids that start collapsed unless the viewer toggled them. */
    static final Set<String> DEFAULT_COLLAPSED = Set.of("requirements", "restrictions", "technical");

    /** One label/value line inside a section. A blank value renders full-width. */
    record Row(String label, String value) {
    }

    /**
     * One drawable block.
     *
     * @param id          stable id, used as the expand/collapse key
     * @param title       section heading
     * @param rows        label/value lines
     * @param paragraphs  wrapped full-width text lines
     * @param collapsible whether the viewer may fold this section away
     * @param expanded    whether it is currently open
     * @param accentColor {@code #RRGGBB} heading accent, or {@code null}
     */
    record Section(String id, String title, List<Row> rows, List<String> paragraphs,
            boolean collapsible, boolean expanded, String accentColor) {

        boolean isEmpty() {
            return rows.isEmpty() && paragraphs.isEmpty();
        }
    }

    private ItemViewSections() {
    }

    /**
     * Builds the ordered, non-empty sections for {@code view}.
     *
     * @param toggled ids whose open/closed state the viewer has flipped away from
     *                its default. Storing the <i>difference</i> rather than the
     *                absolute state is what lets a section be collapsed as well as
     *                expanded — an absolute "expanded" set can only ever open
     *                things, so a section that defaults to open could never be
     *                closed again.
     */
    static List<Section> build(ItemViewData view, ItemViewConfig config, Set<String> toggled) {
        List<Section> out = new ArrayList<>();
        ItemViewConfig.Sections enabled = config.sections;

        if (enabled.classification) {
            add(out, classification(view, config, toggled));
        }
        addCustom(out, view, toggled, ItemViewSection.Placement.AFTER_CLASSIFICATION);

        if (enabled.primaryStatistics) {
            add(out, stats("primary-statistics", "Primary Statistics", view.primaryStats(),
                    view, toggled));
        }
        if (enabled.secondaryStatistics) {
            add(out, stats("secondary-statistics", "Secondary Statistics", view.secondaryStats(),
                    view, toggled));
        }
        addCustom(out, view, toggled, ItemViewSection.Placement.AFTER_STATISTICS);

        if (enabled.modifiers) {
            add(out, modifiers(view, toggled));
        }
        addCustom(out, view, toggled, ItemViewSection.Placement.CUSTOM_MECHANICS);

        if (enabled.durability) {
            add(out, durability(view, toggled));
        }
        if (enabled.requirements) {
            add(out, requirements(view, toggled));
            add(out, restrictions(view, toggled));
        }
        addCustom(out, view, toggled, ItemViewSection.Placement.WITH_REQUIREMENTS);

        if (enabled.description || enabled.lore) {
            add(out, description(view, config, toggled));
        }
        addCustom(out, view, toggled, ItemViewSection.Placement.AFTER_DESCRIPTION);

        if (enabled.technicalInformation) {
            add(out, technical(view, config, toggled));
        }
        addCustom(out, view, toggled, ItemViewSection.Placement.TECHNICAL);

        return out;
    }

    private static void add(List<Section> out, Section section) {
        if (section != null && !section.isEmpty()) {
            out.add(section);
        }
    }

    // ----- Individual sections ---------------------------------------------------

    /**
     * The classification block. Each axis appears only when the item actually has
     * it, and its name is printed exactly as declared — a quality named
     * {@code Null} produces the line {@code Quality  Null}.
     */
    private static Section classification(ItemViewData view, ItemViewConfig config,
            Set<String> toggled) {
        List<Row> rows = new ArrayList<>();
        var data = view.classification();
        var display = config.display;

        if (display.showQuality) {
            data.quality().ifPresentOrElse(
                    quality -> rows.add(new Row("Quality", quality.displayName())),
                    () -> {
                        // Genuinely no quality. Either omit the line, or show the
                        // configured fallback — which is a statement about absence,
                        // never a substitution for an odd-looking real name.
                        if (!config.fallback.hideMissingQuality) {
                            rows.add(new Row("Quality", config.fallback.missingQualityDisplayName));
                        }
                    });
        }
        if (display.showRarity) {
            data.rarity().ifPresent(rarity -> rows.add(new Row("Rarity", rarity.displayName())));
        }
        if (display.showTier) {
            data.tier().ifPresent(tier -> rows.add(new Row("Tier", tier.displayName())));
        }
        if (display.showGrade) {
            data.grade().ifPresent(grade -> rows.add(new Row("Grade", grade.displayName())));
        }
        // An uninterpreted label stays uninterpreted: "Maelstrom — Epic" is shown
        // on one line rather than being split into a guessed tier and rarity.
        data.combinedLabel().ifPresent(label -> rows.add(new Row("Classification", label)));

        data.category().ifPresent(category -> rows.add(new Row("Category", category)));
        data.subcategory().ifPresent(subcategory -> rows.add(new Row("Type", subcategory)));
        data.equipmentSlot().ifPresent(slot -> rows.add(new Row("Slot", slot)));
        view.itemLevel().ifPresent(level -> rows.add(new Row("Item Level", Integer.toString(level))));

        return section("classification", "Item Classification", rows, List.of(), toggled,
                accentOf(view));
    }

    private static Section stats(String id, String title, List<ItemStatEntry> entries,
            ItemViewData view, Set<String> toggled) {
        List<Row> rows = new ArrayList<>();
        for (ItemStatEntry entry : entries) {
            rows.add(new Row(entry.label().plain(), entry.value().plain()));
        }
        return section(id, title, rows, List.of(), toggled, accentOf(view));
    }

    private static Section modifiers(ItemViewData view, Set<String> toggled) {
        List<String> lines = new ArrayList<>();
        for (ItemModifierEntry modifier : view.modifiers()) {
            String source = modifier.source();
            lines.add(source == null || source.isBlank()
                    ? modifier.display()
                    : modifier.display() + "  (" + source + ")");
        }
        return section("modifiers", "Modifiers", List.of(), lines, toggled, accentOf(view));
    }

    /**
     * Durability, shown only when there is something real to show. An unbreakable
     * item or one with no maximum yields no section at all rather than a
     * meaningless {@code 0 / 0}.
     */
    private static Section durability(ItemViewData view, Set<String> toggled) {
        ItemDurabilityData durability = view.durability().orElse(null);
        if (durability == null || !durability.isDisplayable()) {
            return null;
        }
        List<Row> rows = List.of(
                new Row("Durability", durability.display()),
                new Row("Condition", Math.round(durability.fraction() * 100) + "%"));
        return section("durability", "Condition", rows, List.of(), toggled, accentOf(view));
    }

    private static Section requirements(ItemViewData view, Set<String> toggled) {
        List<Row> rows = new ArrayList<>();
        view.requiredLevel().ifPresent(level ->
                rows.add(new Row("Required Level", Integer.toString(level))));
        for (ItemRequirementEntry requirement : view.requirements()) {
            String value = requirement.value().plain();
            Boolean satisfied = requirement.satisfied();
            if (satisfied != null) {
                value = value + (satisfied ? "  (met)" : "  (not met)");
            }
            rows.add(new Row(requirement.label().plain(), value));
        }
        return section("requirements", "Requirements", rows, List.of(), toggled, accentOf(view));
    }

    private static Section restrictions(ItemViewData view, Set<String> toggled) {
        List<Row> rows = new ArrayList<>();
        ItemBindingData binding = view.binding().orElse(null);
        if (binding != null && binding.isDisplayable()) {
            if (binding.bindingType() != null) {
                rows.add(new Row("Binding", binding.bindingType()));
            } else if (binding.bound()) {
                rows.add(new Row("Binding", "Bound"));
            }
            if (binding.boundTo() != null) {
                rows.add(new Row("Bound To", binding.boundTo()));
            }
            if (!binding.tradable()) {
                rows.add(new Row("Tradable", "No"));
            }
            if (!binding.droppable()) {
                rows.add(new Row("Droppable", "No"));
            }
        }
        for (ItemPropertyEntry property : view.properties()) {
            rows.add(new Row(property.label().plain(), property.value().plain()));
        }
        return section("restrictions", "Restrictions", rows, List.of(), toggled, accentOf(view));
    }

    private static Section description(ItemViewData view, ItemViewConfig config,
            Set<String> toggled) {
        List<String> paragraphs = new ArrayList<>();
        if (config.sections.description) {
            view.description().forEach(line -> addLine(paragraphs, line.plain()));
        }
        if (config.sections.lore) {
            view.lore().forEach(line -> addLine(paragraphs, line.plain()));
        }
        return section("description", "Description and Lore", List.of(), paragraphs, toggled,
                accentOf(view));
    }

    private static void addLine(List<String> target, String line) {
        if (line != null && !line.isBlank()) {
            target.add(line);
        }
    }

    private static Section technical(ItemViewData view, ItemViewConfig config,
            Set<String> toggled) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Item ID", view.itemId()));
        view.namespace().ifPresent(namespace -> rows.add(new Row("Namespace", namespace)));
        view.stackLimit().ifPresent(limit -> rows.add(new Row("Stack Limit", Integer.toString(limit))));
        if (view.quantity() > 1) {
            rows.add(new Row("Quantity", Integer.toString(view.quantity())));
        }
        if (config.display.showSourceMod) {
            ItemSourceData source = view.source().orElse(null);
            if (source != null && source.assetPack() != null) {
                rows.add(new Row("Asset Pack", source.assetPack()));
            }
        }
        if (!view.tags().isEmpty()) {
            rows.add(new Row("Tags", String.join(", ", view.tags())));
        }
        for (ItemMetadataEntry entry : view.technicalMetadata()) {
            rows.add(new Row(ItemNames.prettify(entry.key()), entry.value()));
        }
        return section("technical", "Technical Information", rows, List.of(), toggled, null);
    }

    // ----- Provider sections ------------------------------------------------------

    /**
     * Emits the mod-contributed sections whose hint resolves to {@code slot}.
     * Duplicate ids are collapsed so two providers cannot stack identical blocks.
     */
    private static void addCustom(List<Section> out, ItemViewData view, Set<String> toggled,
            ItemViewSection.Placement slot) {
        Set<String> seen = new LinkedHashSet<>();
        for (ItemViewSection custom : view.customSections()) {
            if (custom == null || custom.isEmpty() || custom.placement() != slot) {
                continue;
            }
            String id = "custom:" + custom.id();
            if (!seen.add(id)) {
                continue;
            }
            List<Row> rows = new ArrayList<>();
            for (ItemPropertyEntry row : custom.rows()) {
                rows.add(new Row(row.label().plain(), row.value().plain()));
            }
            List<String> paragraphs = new ArrayList<>();
            custom.paragraphs().forEach(line -> addLine(paragraphs, line.plain()));

            boolean open = resolveOpen(id, !custom.collapsedByDefault(), toggled);
            add(out, new Section(id, custom.title().plain(), rows, paragraphs, true, open,
                    custom.accentColor()));
        }
    }

    // ----- Helpers ----------------------------------------------------------------

    private static Section section(String id, String title, List<Row> rows, List<String> paragraphs,
            Set<String> toggled, String accentColor) {
        boolean defaultOpen = !DEFAULT_COLLAPSED.contains(id);
        // Only the bulky sections get a fold control. A four-row classification
        // block does not need one, and offering it everywhere turns a dense panel
        // into a wall of buttons.
        boolean collapsible = !defaultOpen || "description".equals(id) || "modifiers".equals(id);
        return new Section(id, title, List.copyOf(rows), List.copyOf(paragraphs), collapsible,
                resolveOpen(id, defaultOpen, toggled), accentColor);
    }

    /**
     * Applies the viewer's toggle to a section's default state. Because
     * {@code toggled} holds the difference from the default, one uniform rule
     * covers both directions: a collapsed-by-default section opens, and an
     * open-by-default section closes.
     */
    private static boolean resolveOpen(String id, boolean defaultOpen, Set<String> toggled) {
        return toggled.contains(id) != defaultOpen;
    }

    private static String accentOf(ItemViewData view) {
        return view.accentColor().orElse(null);
    }

    /**
     * The subtitle under the item name: type, then item level. Deliberately does
     * <i>not</i> repeat the quality — that has its own badge.
     */
    static String subtitle(ItemViewData view) {
        List<String> parts = new ArrayList<>();
        var data = view.classification();
        String type = data.subcategory().orElseGet(() -> data.category().orElse(null));
        if (type != null) {
            String category = data.category().orElse(null);
            parts.add(category != null && !category.equals(type) ? type + " " + category : type);
        }
        view.itemLevel().ifPresent(level -> parts.add("Item Level " + level));
        if (parts.isEmpty()) {
            return view.itemId();
        }
        return String.join("  •  ", parts);
    }

    /**
     * The quality badge text, or {@code null} when the badge should be hidden.
     *
     * <p>Present quality &rarr; its name, upper-cased for the badge. Absent
     * quality &rarr; hidden, or the configured fallback if the server chose to
     * show one. The name is never examined to decide which case applies.</p>
     */
    static String qualityBadge(ItemViewData view, ItemViewConfig config) {
        if (!config.display.showQuality) {
            return null;
        }
        ItemClassification quality = view.classification().quality().orElse(null);
        if (quality != null) {
            String name = quality.displayName();
            return name == null || name.isBlank() ? null : name.toUpperCase(java.util.Locale.ROOT);
        }
        if (config.fallback.hideMissingQuality) {
            return null;
        }
        return config.fallback.missingQualityDisplayName.toUpperCase(java.util.Locale.ROOT);
    }
}
