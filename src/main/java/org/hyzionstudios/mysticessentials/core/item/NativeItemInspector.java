package org.hyzionstudios.mysticessentials.core.item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.hyzionstudios.mysticessentials.api.item.ItemClassification;
import org.hyzionstudios.mysticessentials.api.item.ItemNames;
import org.hyzionstudios.mysticessentials.api.item.ItemViewBuilder;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemBindingData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemDurabilityData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemSourceData;
import org.hyzionstudios.mysticessentials.api.item.RichText;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemArmor;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemGlider;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTool;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTranslationProperties;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemUtility;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemWeapon;
import com.hypixel.hytale.server.core.asset.type.item.config.damageData.DamageBreakdown;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

/**
 * Reads everything the engine itself knows about an item into an
 * {@link ItemViewBuilder}: the item definition, the stack's own state, its
 * structured metadata, and its tags.
 *
 * <p>This is the generic stage that runs before any provider, and the reason an
 * unrecognised modded item still produces a useful view. Every accessor is
 * wrapped defensively — asset shapes differ between packs and server builds, and
 * one missing method must degrade a single field, never the whole inspection.</p>
 *
 * <p>Metadata handling follows the model's core rule: a key that is <i>present</i>
 * contributes its value verbatim, even when that value reads {@code "Null"} or
 * {@code "None"}. Only an absent key (or an explicit BSON null) means the item
 * does not have that field.</p>
 */
final class NativeItemInspector {

    private static final String MYSTIC_RPG_GEAR_KEY = "mysticrpg:gear";
    private static final String MYSTIC_RPG_DISPLAY_LORE_KEY = "MysticRPGDisplayLore";
    private static final String MYSTIC_RPG_BASE_DESCRIPTION_KEY = "MysticRPGBaseDescription";

    /** Metadata keys already consumed by a structured field, excluded from the technical dump. */
    private static final Set<String> CONSUMED_KEYS = Set.of(
            "customname", "displayname", "name",
            "lore", "description", "flavor", "flavour",
            "quality", "rarity", "tier", "grade",
            "category", "subcategory", "slot", "equipmentslot",
            "itemlevel", "level", "requiredlevel", "levelrequirement",
            "bound", "boundto", "soulbound", "tradable", "tradeable", "droppable",
            "bindingtype", "modifiers", "affixes");

    private final ItemViewConfig config;

    NativeItemInspector(ItemViewConfig config) {
        this.config = config;
    }

    /** Reads {@code stack} into {@code builder}. Must run on the owning world thread. */
    void inspect(ItemStack stack, ItemViewBuilder builder) {
        Item item = call(stack::getItem, null);
        String itemId = call(stack::getItemId, "");

        builder.itemId(itemId);
        builder.quantity(call(stack::getQuantity, 1));

        readIdentity(item, stack, builder, itemId);
        readClassification(item, builder, itemId);
        readDurability(stack, builder);
        readWeapon(item, builder);
        readArmor(item, builder);
        readTool(item, builder);
        readGlider(item, builder);
        readUtility(item, builder);
        readProperties(item, builder);
        readMetadata(stack, builder);
        readSource(item, builder, itemId);
    }

    // ----- Identity -------------------------------------------------------------

    private void readIdentity(Item item, ItemStack stack, ItemViewBuilder builder, String itemId) {
        ItemDisplayMetadata stackDisplay = call(
                () -> stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC), null);
        Message customName = call(() -> stackDisplay == null ? null : stackDisplay.getName(), null);
        Message customDescription = call(
                () -> stackDisplay == null ? null : stackDisplay.getDescription(), null);

        String translationKey = call(() -> item == null ? null : item.getTranslationKey(), null);
        if (translationKey != null && !translationKey.isBlank()) {
            builder.displayName(RichText.translated(translationKey, nameArguments(item)));
        } else {
            String prettified = ItemNames.prettify(itemId);
            if (!prettified.isBlank()) {
                builder.displayName(prettified);
            }
        }

        String icon = call(() -> item == null ? null : item.getIcon(), null);
        builder.iconAsset(icon);
        builder.stackLimit(call(() -> item == null ? 0 : item.getMaxStack(), 0));

        if (customDescription == null) {
            String descriptionKey = call(
                    () -> item == null ? null : item.getDescriptionTranslationKey(), null);
            if (descriptionKey != null && !descriptionKey.isBlank()) {
                builder.description(RichText.translated(descriptionKey, descriptionArguments(item)));
            }
        }

        int itemLevel = call(() -> item == null ? 0 : item.getItemLevel(), 0);
        if (itemLevel > 0) {
            builder.itemLevel(itemLevel);
        }

        // Stack-level ItemDisplay metadata is the engine's standard override
        // channel. Reforging/enchantment mods use it even when the base asset
        // already has a translation key, so it must be authoritative here too.
        RichText resolvedName = messageRichText(customName);
        if (resolvedName != null && !resolvedName.isEmpty()) {
            builder.displayName(resolvedName);
        } else if (translationKey == null) {
            resolvedName = messageRichText(call(stack::getDisplayName, null));
            if (resolvedName != null && !resolvedName.isEmpty()) {
                builder.displayName(resolvedName);
            }
        }

        RichText resolvedDescription = mysticRpgBaseDescription(stack, customDescription);
        if (resolvedDescription != null && !resolvedDescription.isEmpty()) {
            builder.description(resolvedDescription);
        }
    }

    /**
     * MysticRPG composes its generated rarity/affix block into ItemDisplay so
     * the vanilla tooltip can render it. ItemView promotes that block into
     * structured sections, therefore only the preserved base description (or
     * generated flavour before the block) belongs in Description and Lore.
     */
    @SuppressWarnings("deprecation") // Raw BSON remains required for MysticRPG's published stack keys.
    private static RichText mysticRpgBaseDescription(ItemStack stack, Message composed) {
        BsonDocument metadata = call(() -> stack == null ? null : stack.getMetadata(), null);
        if (metadata == null || metadata.get(MYSTIC_RPG_GEAR_KEY) == null) {
            return messageRichText(composed);
        }

        Message storedBase = call(() -> {
            BsonValue encoded = metadata.get(MYSTIC_RPG_BASE_DESCRIPTION_KEY);
            return encoded == null || encoded.isNull()
                    ? null
                    : Message.CODEC.decode(encoded, new com.hypixel.hytale.codec.ExtraInfo());
        }, null);
        RichText base = messageRichText(storedBase);
        if (base != null && !base.isEmpty()) {
            return base;
        }

        RichText visible = messageRichText(composed);
        if (visible == null || visible.isEmpty()) {
            return visible;
        }
        BsonValue storedLore = metadata.get(MYSTIC_RPG_DISPLAY_LORE_KEY);
        if (storedLore == null || !storedLore.isString()) {
            return visible;
        }
        String plain = visible.plain();
        String stripped = withoutMysticRpgLore(plain, storedLore.asString().getValue());
        return stripped.equals(plain) ? visible : RichText.plain(stripped);
    }

    /** Package-visible for the metadata contract compatibility check. */
    static String withoutMysticRpgLore(String composed, String mysticLore) {
        if (composed == null || composed.isBlank() || mysticLore == null || mysticLore.isBlank()) {
            return composed == null ? "" : composed;
        }
        int start = composed.lastIndexOf(mysticLore);
        if (start < 0) {
            return composed;
        }
        String before = composed.substring(0, start).stripTrailing();
        String after = composed.substring(start + mysticLore.length()).stripLeading();
        if (before.isEmpty()) {
            return after;
        }
        if (after.isEmpty()) {
            return before;
        }
        return before + "\n\n" + after;
    }

    /**
     * The parameters that fill a templated item name (e.g. {@code {material}} in
     * "{material} Longsword"). Without them the client renders the raw
     * placeholder. Values are encoded {@code @key} (nested translation) or
     * {@code ~literal}, matching {@code Message.param}.
     */
    private static Map<String, String> nameArguments(Item item) {
        return encodeArguments(call(() -> {
            ItemTranslationProperties properties = item == null ? null : item.getTranslationProperties();
            return properties == null ? null : properties.getNameArguments();
        }, null));
    }

    private static Map<String, String> descriptionArguments(Item item) {
        return encodeArguments(call(() -> {
            ItemTranslationProperties properties = item == null ? null : item.getTranslationProperties();
            return properties == null ? null : properties.getDescriptionArguments();
        }, null));
    }

    private static Map<String, String> encodeArguments(Map<String, Message> arguments) {
        Map<String, String> out = new LinkedHashMap<>();
        if (arguments == null) {
            return out;
        }
        for (Map.Entry<String, Message> entry : arguments.entrySet()) {
            String name = sanitizeArgument(entry.getKey());
            Message value = entry.getValue();
            if (name.isEmpty() || value == null) {
                continue;
            }
            FormattedMessage formatted = call(value::getFormattedMessage, null);
            if (formatted == null) {
                continue;
            }
            if (formatted.messageId != null && !formatted.messageId.isBlank()) {
                out.put(name, "@" + sanitizeArgument(formatted.messageId));
            } else if (formatted.rawText != null && !formatted.rawText.isBlank()) {
                out.put(name, "~" + sanitizeArgument(formatted.rawText));
            }
        }
        return out;
    }

    /** Strips the delimiters used by the {@code <lang:key|name=value>} encoding. */
    private static String sanitizeArgument(String value) {
        return value == null ? "" : value.replaceAll("[<>|=]", "").trim();
    }

    /** Preserves translation segments and flattens composite raw messages safely. */
    private static RichText messageRichText(Message message) {
        FormattedMessage formatted = call(() -> message == null ? null : message.getFormattedMessage(), null);
        return formatted == null ? null : formattedRichText(formatted);
    }

    /** Package-visible for the composite translated-name compatibility check. */
    static RichText formattedRichText(FormattedMessage formatted) {
        RichText out = RichText.empty();
        if (formatted.rawText != null && !formatted.rawText.isEmpty()) {
            out = out.append(RichText.plain(formatted.rawText));
        } else if (formatted.messageId != null && !formatted.messageId.isBlank()) {
            out = out.append(RichText.translated(formatted.messageId,
                    formattedArguments(formatted)));
        }
        if (formatted.children != null) {
            for (FormattedMessage child : formatted.children) {
                if (child != null) {
                    out = out.append(formattedRichText(child));
                }
            }
        }
        return out;
    }

    /** Retains message-valued parameters when a translated name is nested in a composite name. */
    private static Map<String, String> formattedArguments(FormattedMessage formatted) {
        Map<String, String> out = new LinkedHashMap<>();
        if (formatted == null || formatted.messageParams == null) {
            return out;
        }
        for (Map.Entry<String, FormattedMessage> entry : formatted.messageParams.entrySet()) {
            String name = sanitizeArgument(entry.getKey());
            FormattedMessage value = entry.getValue();
            if (name.isEmpty() || value == null) {
                continue;
            }
            if (value.messageId != null && !value.messageId.isBlank()) {
                out.put(name, "@" + sanitizeArgument(value.messageId));
                continue;
            }
            RichText literal = formattedRichText(value);
            if (literal != null && !literal.plain().isBlank()) {
                out.put(name, "~" + sanitizeArgument(literal.plain()));
            }
        }
        return out;
    }

    // ----- Classification -------------------------------------------------------

    private void readClassification(Item item, ItemViewBuilder builder, String itemId) {
        Integer qualityIndex = call(() -> item == null ? null : item.getQualityIndex(), null);
        if (qualityIndex != null) {
            ItemViewConfig.QualityDefinition definition = qualityFor(qualityIndex);
            ItemClassification nativeQuality = nativeQuality(qualityIndex);
            // Runtime asset identity is authoritative. Index allocation can move
            // when packs add qualities, so an index-only configured definition
            // is used only when its id still names the asset at that index.
            if (definition != null && matches(definition, nativeQuality)) {
                builder.quality(ItemClassification.builder()
                        .id(definition.id)
                        .displayName(definition.name)
                        .color(definition.color)
                        .accentColor(definition.accentColor)
                        .sortOrder(definition.index)
                        .build());
            } else if (nativeQuality != null) {
                builder.quality(nativeQuality);
            }
        }

        applyClassificationRules(builder, itemId);

        String category = firstCategory(item);
        if (category != null) {
            builder.category(category);
        }
        String subCategory = call(() -> item == null ? null : item.getSubCategory(), null);
        if (subCategory != null && !subCategory.isBlank()) {
            builder.subcategory(ItemNames.prettify(subCategory));
        }
    }

    private ItemViewConfig.QualityDefinition qualityFor(int index) {
        if (config.qualities == null) {
            return null;
        }
        for (ItemViewConfig.QualityDefinition definition : config.qualities) {
            if (definition != null && definition.index == index) {
                return definition;
            }
        }
        return null;
    }

    private static boolean matches(ItemViewConfig.QualityDefinition configured,
            ItemClassification nativeQuality) {
        if (configured == null || nativeQuality == null) {
            return configured != null;
        }
        if (configured.id == null || configured.id.isBlank()) {
            return true;
        }
        String configuredId = configured.id;
        int colon = configuredId.indexOf(':');
        if (colon >= 0 && colon + 1 < configuredId.length()) {
            configuredId = configuredId.substring(colon + 1);
        }
        String localConfiguredId = configuredId;
        return nativeQuality.id() != null
                && nativeQuality.id().equalsIgnoreCase(localConfiguredId);
    }

    private static ItemClassification nativeQuality(int index) {
        ItemQuality quality = call(() -> ItemQuality.getAssetMap().getAsset(index), null);
        if (quality == null) {
            return null;
        }
        String id = call(quality::getId, null);
        String name = ItemNames.prettify(id);
        if (name.isBlank()) {
            return null;
        }
        return ItemClassification.builder()
                .id(id)
                .displayName(name)
                .color(color(call(quality::getTextColor, null)))
                .sortOrder(call(quality::getQualityValue, index))
                .build();
    }

    private static String color(com.hypixel.hytale.protocol.Color color) {
        if (color == null) {
            return null;
        }
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                Byte.toUnsignedInt(color.red), Byte.toUnsignedInt(color.green),
                Byte.toUnsignedInt(color.blue));
    }

    /**
     * Applies the first matching id rule. A rule contributes only the axes it
     * actually names, so a rule that knows the rarity does not fabricate a tier.
     */
    private void applyClassificationRules(ItemViewBuilder builder, String itemId) {
        if (config.classificationRules == null || itemId == null || itemId.isBlank()) {
            return;
        }
        String lower = itemId.toLowerCase(Locale.ROOT);
        for (ItemViewConfig.ClassificationRule rule : config.classificationRules) {
            if (rule == null || rule.match == null || rule.match.isBlank()) {
                continue;
            }
            boolean matched;
            try {
                matched = rule.regex
                        ? lower.matches(rule.match.toLowerCase(Locale.ROOT))
                        : lower.contains(rule.match.toLowerCase(Locale.ROOT));
            } catch (RuntimeException badPattern) {
                matched = false;
            }
            if (!matched) {
                continue;
            }
            if (rule.quality != null) {
                builder.quality(styled(rule.quality, rule.color));
            }
            if (rule.rarity != null) {
                builder.rarity(styled(rule.rarity, rule.color));
            }
            if (rule.tier != null) {
                builder.tier(styled(rule.tier, rule.color));
            }
            if (rule.grade != null) {
                builder.grade(styled(rule.grade, rule.color));
            }
            return;
        }
    }

    private static ItemClassification styled(String name, String color) {
        return ItemClassification.builder().displayName(name).color(color).build();
    }

    private static String firstCategory(Item item) {
        String[] categories = call(() -> item == null ? null : item.getCategories(), null);
        if (categories == null || categories.length == 0) {
            return null;
        }
        String first = ItemNames.prettify(categories[0]);
        return first.isBlank() ? null : first;
    }

    // ----- Stack state ----------------------------------------------------------

    private void readDurability(ItemStack stack, ItemViewBuilder builder) {
        double current = call(stack::getDurability, 0d);
        double maximum = call(stack::getMaxDurability, 0d);
        boolean unbreakable = call(stack::isUnbreakable, false);
        // Recorded even when it is not displayable, so a provider can reason about
        // it; the renderer is what decides to hide a 0/0 bar.
        builder.durability(new ItemDurabilityData(current, maximum, unbreakable));
    }

    // ----- Type-specific statistics ---------------------------------------------

    private void readWeapon(Item item, ItemViewBuilder builder) {
        ItemWeapon weapon = call(() -> item == null ? null : item.getWeapon(), null);
        if (weapon == null) {
            return;
        }
        addDamage(builder, call(weapon::getBasicDamageBreakdown, null), "Basic Damage", true);
        addDamage(builder, call(weapon::getUltimateDamageBreakdown, null), "Charged Damage", true);
        addStatModifiers(builder, call(weapon::getStatModifiers, null), "Weapon");
    }

    private void addDamage(ItemViewBuilder builder, DamageBreakdown breakdown, String fallbackLabel,
            boolean primary) {
        if (breakdown == null) {
            return;
        }
        List<DamageBreakdown.Entry> entries = call(breakdown::entries, null);
        if (entries == null) {
            return;
        }
        for (DamageBreakdown.Entry entry : entries) {
            if (entry == null) {
                continue;
            }
            String label = ItemNames.prettifyKey(call(entry::labelKey, null));
            if (label.isBlank()) {
                label = fallbackLabel;
            } else {
                label = fallbackLabel + " (" + label + ")";
            }
            String value = ItemNames.range(call(entry::min, 0f), call(entry::max, 0f));
            if (primary) {
                builder.addPrimaryStat(label, value);
            } else {
                builder.addSecondaryStat(label, value);
            }
        }
    }

    private void readArmor(Item item, ItemViewBuilder builder) {
        ItemArmor armor = call(() -> item == null ? null : item.getArmor(), null);
        if (armor == null) {
            return;
        }
        double resistance = call(armor::getBaseDamageResistance, 0d);
        if (resistance != 0) {
            builder.addPrimaryStat("Damage Resistance", resistance);
        }
        Object slot = call(armor::getArmorSlot, null);
        if (slot != null) {
            builder.equipmentSlot(ItemNames.prettify(String.valueOf(slot)));
        }
        addStatModifiers(builder, call(armor::getStatModifiers, null), "Armor");
    }

    private void readTool(Item item, ItemViewBuilder builder) {
        ItemTool tool = call(() -> item == null ? null : item.getTool(), null);
        if (tool == null) {
            return;
        }
        float speed = call(tool::getSpeed, 0f);
        if (speed != 0) {
            builder.addPrimaryStat("Speed", speed);
        }
    }

    private void readGlider(Item item, ItemViewBuilder builder) {
        ItemGlider glider = call(() -> item == null ? null : item.getGlider(), null);
        if (glider == null) {
            return;
        }
        builder.addSecondaryStat("Terminal Velocity", call(glider::getTerminalVelocity, 0f));
        builder.addSecondaryStat("Fall Speed Multiplier", call(glider::getFallSpeedMultiplier, 0f));
        builder.addSecondaryStat("Horizontal Speed Multiplier",
                call(glider::getHorizontalSpeedMultiplier, 0f));
    }

    private void readUtility(Item item, ItemViewBuilder builder) {
        ItemUtility utility = call(() -> item == null ? null : item.getUtility(), null);
        if (utility == null) {
            return;
        }
        addStatModifiers(builder, call(utility::getStatModifiers, null), "Utility");
    }

    /**
     * Turns the engine's stat-index-keyed modifier map into named modifier rows.
     * The index is resolved through the {@link EntityStatType} lookup table, so
     * an unregistered stat yields a readable {@code Stat #7} rather than being
     * dropped — unknown data is still data.
     */
    private void addStatModifiers(ItemViewBuilder builder,
            Int2ObjectMap<StaticModifier[]> statModifiers, String source) {
        if (statModifiers == null || statModifiers.isEmpty()) {
            return;
        }
        try {
            for (Int2ObjectMap.Entry<StaticModifier[]> entry : statModifiers.int2ObjectEntrySet()) {
                StaticModifier[] modifiers = entry.getValue();
                if (modifiers == null) {
                    continue;
                }
                String statName = statName(entry.getIntKey());
                for (StaticModifier modifier : modifiers) {
                    if (modifier == null) {
                        continue;
                    }
                    float amount = call(modifier::getAmount, 0f);
                    if (amount == 0) {
                        continue;
                    }
                    boolean multiplicative = String.valueOf(call(modifier::getCalculationType, null))
                            .toUpperCase(Locale.ROOT).startsWith("MULT");
                    builder.addModifier(statName, amount, multiplicative, source);
                }
            }
        } catch (Throwable ignored) {
            // A modifier-map shape this build does not expose costs the modifier
            // rows, not the inspection.
        }
    }

    private static String statName(int index) {
        String id = call(() -> {
            EntityStatType type = EntityStatType.getAssetMap().getAsset(index);
            return type == null ? null : type.getId();
        }, null);
        if (id == null || id.isBlank()) {
            return "Stat #" + index;
        }
        return ItemNames.prettify(id);
    }

    // ----- Properties, metadata, source -----------------------------------------

    private void readProperties(Item item, ItemViewBuilder builder) {
        if (item == null) {
            return;
        }
        if (call(item::isConsumable, false)) {
            builder.addProperty("Consumable", "Yes");
            builder.addTag("consumable");
        }
        if (!call(item::isRepairable, true)) {
            builder.addProperty("Repairable", "No");
        }
        if (!call(item::dropsOnDeath, true)) {
            builder.addProperty("Drops on Death", "No");
        }
        double fuelQuality = call(item::getFuelQuality, 0d);
        if (fuelQuality > 0) {
            builder.addSecondaryStat("Fuel Quality", fuelQuality);
        }
        if (call(item::hasBlockType, false)) {
            builder.addTag("placeable");
        }
        readAssetTags(item, builder);
    }

    /** Imports an asset pack's declared item tags for providers and API callers. */
    private void readAssetTags(Item item, ItemViewBuilder builder) {
        Map<String, String[]> tags = call(() -> {
            var data = item == null ? null : item.getData();
            return data == null ? null : data.getRawTags();
        }, null);
        if (tags == null) {
            return;
        }
        for (Map.Entry<String, String[]> entry : tags.entrySet()) {
            String family = entry.getKey();
            String[] values = entry.getValue();
            if (family == null || family.isBlank() || values == null) {
                continue;
            }
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    builder.addTag(family + ":" + value);
                }
            }
        }
    }

    private void readSource(Item item, ItemViewBuilder builder, String itemId) {
        String assetPack = config.display.showSourceMod ? sourceFromTranslation(item) : null;
        builder.source(new ItemSourceData(ItemNames.namespaceOf(itemId),
                assetPack, null));
    }

    /**
     * Item ids are global rather than namespaced in most Hytale packs. Their
     * translation keys are the stable, client-visible provenance signal used by
     * the supplied custom item packs (for example {@code MajorDungeons.items}).
     */
    private static String sourceFromTranslation(Item item) {
        String key = call(() -> item == null ? null : item.getTranslationKey(), null);
        if (key == null || key.isBlank()) {
            return null;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        int items = lower.indexOf(".items.");
        if (items <= 0) {
            return null;
        }
        String source = key.substring(0, items);
        return switch (source.toLowerCase(Locale.ROOT)) {
            case "item", "items", "server", "hytale" -> null;
            default -> source;
        };
    }

    /**
     * Reads the stack's BSON metadata: recognised keys become structured fields,
     * everything else is surfaced verbatim in the Technical Information section.
     *
     * <p>A present key contributes its value even when the value reads
     * {@code "Null"} — that is a name, not an absence. Only a missing key or an
     * explicit BSON null means the item lacks the field.</p>
     */
    @SuppressWarnings("deprecation") // Hytale has no non-deprecated API for enumerating all stack metadata yet.
    private void readMetadata(ItemStack stack, ItemViewBuilder builder) {
        BsonDocument metadata = call(stack::getMetadata, null);
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        boolean bound = false;
        String bindingType = null;
        String boundTo = null;
        boolean tradable = true;
        boolean droppable = true;

        for (Map.Entry<String, BsonValue> entry : metadata.entrySet()) {
            String key = entry.getKey();
            BsonValue value = entry.getValue();
            if (key == null || value == null || value.isNull()) {
                // An explicit null IS absence — skip without recording anything.
                continue;
            }
            String normalized = key.toLowerCase(Locale.ROOT);
            boolean promotedByModReader = ModItemMetadataInspector.inspect(key, value, builder);
            switch (normalized) {
                case "customname", "displayname", "name" -> {
                    String text = text(value);
                    if (text != null) {
                        builder.displayName(text);
                    }
                }
                case "lore", "flavor", "flavour" -> forEachLine(value, builder::lore);
                case "description" -> forEachLine(value, builder::description);
                case "quality" -> classificationFrom(value).ifPresent(builder::quality);
                case "rarity" -> classificationFrom(value).ifPresent(builder::rarity);
                case "tier" -> classificationFrom(value).ifPresent(builder::tier);
                case "grade" -> classificationFrom(value).ifPresent(builder::grade);
                case "category" -> builder.category(text(value));
                case "subcategory" -> builder.subcategory(text(value));
                case "slot", "equipmentslot" -> builder.equipmentSlot(text(value));
                case "itemlevel", "level" -> integer(value).ifPresent(builder::itemLevel);
                case "requiredlevel", "levelrequirement" ->
                        integer(value).ifPresent(builder::requiredLevel);
                case "bound", "soulbound" -> bound = bool(value, false);
                case "bindingtype" -> bindingType = text(value);
                case "boundto" -> boundTo = text(value);
                case "tradable", "tradeable" -> tradable = bool(value, true);
                case "droppable" -> droppable = bool(value, true);
                case "modifiers", "affixes" -> readModifierDocument(value, builder);
                default -> { /* falls through to the technical dump below */ }
            }
            if (!promotedByModReader && !CONSUMED_KEYS.contains(normalized)) {
                builder.addTechnical(key, describe(value));
            }
        }

        if (bound || bindingType != null || boundTo != null || !tradable || !droppable) {
            builder.binding(new ItemBindingData(bound, bindingType, boundTo, tradable, droppable));
        }
    }

    /** Reads {@code {"haste": 8.1, "defense": -4.8}} into modifier rows. */
    private void readModifierDocument(BsonValue value, ItemViewBuilder builder) {
        if (!value.isDocument()) {
            return;
        }
        for (Map.Entry<String, BsonValue> entry : value.asDocument().entrySet()) {
            Double amount = number(entry.getValue());
            if (amount != null) {
                builder.addModifier(entry.getKey(), amount);
            }
        }
    }

    private static void forEachLine(BsonValue value, java.util.function.Consumer<String> sink) {
        if (value.isArray()) {
            BsonArray array = value.asArray();
            for (BsonValue element : array) {
                String text = text(element);
                if (text != null) {
                    sink.accept(text);
                }
            }
            return;
        }
        String text = text(value);
        if (text != null) {
            for (String line : text.split("\\R", -1)) {
                if (!line.isBlank()) {
                    sink.accept(line);
                }
            }
        }
    }

    /**
     * Builds a classification from a metadata value. A present string always
     * produces one, whatever it spells — {@code "Null"} yields a quality named
     * {@code Null}. Only an absent or non-scalar value yields nothing.
     */
    private static java.util.Optional<ItemClassification> classificationFrom(BsonValue value) {
        if (value.isDocument()) {
            BsonDocument document = value.asDocument();
            String name = text(document.get("name"));
            String id = text(document.get("id"));
            if (name == null && id == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(ItemClassification.builder()
                    .id(id)
                    .displayName(name)
                    .color(text(document.get("color")))
                    .icon(text(document.get("icon")))
                    .build());
        }
        String text = text(value);
        if (text == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(ItemClassification.named(text));
    }

    // ----- BSON helpers ---------------------------------------------------------

    private static String text(BsonValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            if (value.isString()) {
                String raw = value.asString().getValue();
                return raw == null || raw.isBlank() ? null : raw;
            }
            if (value.isInt32() || value.isInt64() || value.isDouble()) {
                return describe(value);
            }
            if (value.isBoolean()) {
                return value.asBoolean().getValue() ? "Yes" : "No";
            }
        } catch (Throwable ignored) {
            // Unknown BSON shapes are treated as absent rather than crashing.
        }
        return null;
    }

    private static java.util.Optional<Integer> integer(BsonValue value) {
        Double number = number(value);
        return number == null ? java.util.Optional.empty()
                : java.util.Optional.of((int) Math.round(number));
    }

    private static Double number(BsonValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            if (value.isInt32()) {
                return (double) value.asInt32().getValue();
            }
            if (value.isInt64()) {
                return (double) value.asInt64().getValue();
            }
            if (value.isDouble()) {
                return value.asDouble().getValue();
            }
            if (value.isString()) {
                return Double.parseDouble(value.asString().getValue().trim());
            }
        } catch (Throwable ignored) {
            // Not a number; the caller treats this as an absent numeric field.
        }
        return null;
    }

    private static boolean bool(BsonValue value, boolean fallback) {
        try {
            if (value.isBoolean()) {
                return value.asBoolean().getValue();
            }
            String text = text(value);
            if (text != null) {
                return "yes".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text);
            }
        } catch (Throwable ignored) {
            // Fall through to the caller's default.
        }
        return fallback;
    }

    /** A short, safe rendering of any BSON value for the technical section. */
    private static String describe(BsonValue value) {
        try {
            if (value.isString()) {
                return value.asString().getValue();
            }
            if (value.isInt32()) {
                return Integer.toString(value.asInt32().getValue());
            }
            if (value.isInt64()) {
                return Long.toString(value.asInt64().getValue());
            }
            if (value.isDouble()) {
                return ItemNames.number(value.asDouble().getValue());
            }
            if (value.isBoolean()) {
                return Boolean.toString(value.asBoolean().getValue());
            }
            if (value.isArray()) {
                return value.asArray().size() + " entries";
            }
            if (value.isDocument()) {
                return value.asDocument().size() + " fields";
            }
            return value.getBsonType().name();
        } catch (Throwable t) {
            return "<unreadable>";
        }
    }

    // ----- Defensive invocation --------------------------------------------------

    @FunctionalInterface
    private interface Reader<T> {
        T read() throws Throwable;
    }

    /** Invokes {@code reader}, returning {@code fallback} if it throws or yields null. */
    private static <T> T call(Reader<T> reader, T fallback) {
        try {
            T value = reader.read();
            return value == null ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
