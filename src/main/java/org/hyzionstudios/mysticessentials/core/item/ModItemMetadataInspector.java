package org.hyzionstudios.mysticessentials.core.item;

import java.util.Locale;
import java.util.Map;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.hyzionstudios.mysticessentials.api.item.ItemClassification;
import org.hyzionstudios.mysticessentials.api.item.ItemNames;
import org.hyzionstudios.mysticessentials.api.item.ItemViewBuilder;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemBindingData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewEntries.ItemDurabilityData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewSection;

/**
 * Promotes well-known, portable mod metadata formats into structured ItemView
 * data without linking MysticEssentials to the mod that wrote them.
 *
 * <p>The formats handled here are deliberately identified by their on-stack
 * BSON contract rather than by implementation classes. That keeps inspection
 * working across Hytale's isolated plugin class loaders and also lets compatible
 * third-party producers use the same documents.</p>
 */
final class ModItemMetadataInspector {

    private static final String ENCHANTMENTS_KEY = "Enchantments";
    private static final String LUX_REFORGE_KEY = "LuxReforge";
    private static final String MYSTIC_RPG_GEAR_KEY = "mysticrpg:gear";
    private static final String MYSTIC_RPG_DISPLAY_LORE_KEY = "MysticRPGDisplayLore";
    private static final String MYSTIC_RPG_BASE_NAME_KEY = "MysticRPGBaseName";
    private static final String MYSTIC_RPG_BASE_DESCRIPTION_KEY = "MysticRPGBaseDescription";
    private static final String MYSTIC_RPG_DISPLAY_VERSION_KEY = "MysticRPGDisplayVersion";
    private static final int MAX_MYSTIC_RPG_PAYLOAD_LENGTH = 1_048_576;

    /** MysticRPG's built-in registry content; custom affixes use the readable fallback below. */
    private static final Map<String, MysticAffix> MYSTIC_RPG_AFFIXES = Map.ofEntries(
            Map.entry("mysticrpg:stormforged",
                    new MysticAffix("Physical Damage", "Stormforged", false)),
            Map.entry("mysticrpg:brutal",
                    new MysticAffix("Strength", "Brutal", false)),
            Map.entry("mysticrpg:bulwark",
                    new MysticAffix("Armor", "Bulwark", false)),
            Map.entry("mysticrpg:arcane",
                    new MysticAffix("Magical Damage", "Arcane", false)),
            Map.entry("mysticrpg:vigorous",
                    new MysticAffix("Maximum Health", "Vigorous", false)),
            Map.entry("mysticrpg:of_the_vanguard",
                    new MysticAffix("Critical Damage", "of the Vanguard", true)),
            Map.entry("mysticrpg:of_warding",
                    new MysticAffix("Magic Resistance", "of Warding", true)),
            Map.entry("mysticrpg:of_precision",
                    new MysticAffix("Critical Chance", "of Precision", true)),
            Map.entry("mysticrpg:of_the_scholar",
                    new MysticAffix("Intelligence", "of the Scholar", false)),
            Map.entry("mysticrpg:of_haste",
                    new MysticAffix("Attack Speed", "of Haste", true)),
            Map.entry("mysticrpg:of_fortune",
                    new MysticAffix("Item Find", "of Fortune", true)));

    private ModItemMetadataInspector() {
    }

    /** @return whether {@code key} was recognized and promoted. */
    static boolean inspect(String key, BsonValue value, ItemViewBuilder builder) {
        if (key == null || value == null) {
            return false;
        }
        if (MYSTIC_RPG_GEAR_KEY.equalsIgnoreCase(key)) {
            return readMysticRpgGear(value, builder);
        }
        if (MYSTIC_RPG_DISPLAY_LORE_KEY.equalsIgnoreCase(key)
                || MYSTIC_RPG_BASE_NAME_KEY.equalsIgnoreCase(key)
                || MYSTIC_RPG_BASE_DESCRIPTION_KEY.equalsIgnoreCase(key)
                || MYSTIC_RPG_DISPLAY_VERSION_KEY.equalsIgnoreCase(key)) {
            // Companion presentation state is internal bookkeeping. The gear
            // stamp above is the structured source of truth for this panel.
            return true;
        }
        if (!value.isDocument()) {
            return false;
        }
        if (ENCHANTMENTS_KEY.equalsIgnoreCase(key)) {
            return readEnchantments(value.asDocument(), builder);
        }
        if (LUX_REFORGE_KEY.equalsIgnoreCase(key)) {
            return readLuxReforge(value.asDocument(), builder);
        }
        return false;
    }

    /**
     * MysticRPG GearStamp v1 contract. The stack value is a JSON string rather
     * than a BSON document so it can pass through servers that do not load the
     * RPG plugin. We parse only presentation fields and never attempt to verify
     * or mutate the integrity signature owned by MysticRPG.
     */
    private static boolean readMysticRpgGear(BsonValue value, ItemViewBuilder builder) {
        BsonDocument envelope = document(value);
        if (envelope == null) {
            return false;
        }
        BsonValue nestedItem = getIgnoreCase(envelope, "item");
        BsonDocument item = nestedItem != null && nestedItem.isDocument()
                ? nestedItem.asDocument() : envelope;

        String template = string(item, "template");
        String rarity = string(item, "rarity");
        String quality = string(item, "quality");
        BsonValue affixes = getIgnoreCase(item, "affixes");
        boolean recognized = template != null || rarity != null || quality != null
                || getIgnoreCase(item, "instance") != null || affixes != null;
        if (!recognized) {
            return false;
        }

        if (rarity != null) {
            builder.rarity(ItemClassification.builder()
                    .id("mysticrpg:rarity/" + rarity.toLowerCase(Locale.ROOT))
                    .displayName(enumName(rarity))
                    .color(mysticRarityColor(rarity))
                    .build());
        }
        if (quality != null) {
            builder.quality(ItemClassification.builder()
                    .id("mysticrpg:quality/" + quality.toLowerCase(Locale.ROOT))
                    .displayName(enumName(quality))
                    .build());
        }

        Integer itemLevel = integer(getIgnoreCase(item, "ilvl"));
        if (itemLevel != null && itemLevel > 0) {
            builder.itemLevel(itemLevel);
        }
        readMysticRpgAffixes(affixes, builder);
        readMysticRpgDurability(item, builder);
        readMysticRpgBinding(item, builder);
        readMysticRpgDetails(item, builder);
        builder.addTag("mysticrpg-gear");
        return true;
    }

    private static BsonDocument document(BsonValue value) {
        if (value.isDocument()) {
            return value.asDocument();
        }
        if (!value.isString()) {
            return null;
        }
        String payload = value.asString().getValue();
        if (payload == null || payload.isBlank()
                || payload.length() > MAX_MYSTIC_RPG_PAYLOAD_LENGTH) {
            return null;
        }
        try {
            return BsonDocument.parse(payload);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static void readMysticRpgAffixes(BsonValue value, ItemViewBuilder builder) {
        if (value == null || !value.isArray()) {
            return;
        }
        for (BsonValue entry : value.asArray()) {
            if (!entry.isDocument()) {
                continue;
            }
            BsonDocument roll = entry.asDocument();
            String affixId = string(roll, "affix");
            Double amount = number(getIgnoreCase(roll, "value"));
            if (affixId == null || amount == null || !Double.isFinite(amount)) {
                continue;
            }

            MysticAffix known = MYSTIC_RPG_AFFIXES.get(affixId.toLowerCase(Locale.ROOT));
            String statistic = known == null ? ItemNames.prettify(localId(affixId)) : known.statistic();
            String source = known == null ? ItemNames.prettify(localId(affixId)) : known.displayName();
            Integer tier = integer(getIgnoreCase(roll, "tier"));
            if (tier != null && tier > 0) {
                source += " · Tier " + tier;
            }
            if (bool(getIgnoreCase(roll, "locked"), false)) {
                source += " · Locked";
            }
            builder.addModifier(statistic, amount, known != null && known.percentage(), source);
        }
    }

    private static void readMysticRpgDurability(BsonDocument item, ItemViewBuilder builder) {
        Double maximum = number(getIgnoreCase(item, "maxDurability"));
        if (maximum == null || maximum <= 0) {
            return;
        }
        Double current = number(getIgnoreCase(item, "durability"));
        builder.durability(new ItemDurabilityData(current == null ? maximum : current,
                maximum, false));
    }

    private static void readMysticRpgBinding(BsonDocument item, ItemViewBuilder builder) {
        String binding = string(item, "binding");
        BsonValue states = getIgnoreCase(item, "states");
        boolean explicitlyBound = containsIgnoreCase(states, "BOUND");
        boolean bindingIsBound = binding != null && switch (binding.toUpperCase(Locale.ROOT)) {
            case "BIND_ON_PICKUP", "BOUND_TO_PLAYER", "BOUND_TO_ACCOUNT" -> true;
            default -> false;
        };
        boolean bound = explicitlyBound || bindingIsBound;
        if (binding == null && !bound) {
            return;
        }
        builder.binding(new ItemBindingData(bound,
                binding == null ? null : enumName(binding),
                bound ? string(item, "owner") : null,
                !bound, true));
    }

    private static void readMysticRpgDetails(BsonDocument item, ItemViewBuilder builder) {
        ItemViewSection.Builder section = ItemViewSection
                .builder("native:mysticrpg", "MysticRPG Gear")
                .placement(ItemViewSection.Placement.CUSTOM_MECHANICS);
        int rows = 0;

        BsonValue states = getIgnoreCase(item, "states");
        if (states != null && states.isArray() && !states.asArray().isEmpty()) {
            section.row("States", joinEnumNames(states.asArray()));
            rows++;
        }
        BsonValue sockets = getIgnoreCase(item, "sockets");
        if (sockets != null && sockets.isArray() && !sockets.asArray().isEmpty()) {
            int filled = 0;
            for (BsonValue socket : sockets.asArray()) {
                if (socket.isDocument() && string(socket.asDocument(), "gem") != null) {
                    filled++;
                }
            }
            section.row("Sockets", filled + " / " + sockets.asArray().size() + " filled");
            rows++;
        }
        BsonValue skills = getIgnoreCase(item, "skills");
        if (skills != null && skills.isArray() && !skills.asArray().isEmpty()) {
            section.row("Embedded Skills", Integer.toString(skills.asArray().size()));
            rows++;
        }
        String source = string(item, "source");
        if (source != null) {
            section.row("Creation Source", enumName(source));
            rows++;
        }
        Long seed = longNumber(getIgnoreCase(item, "seed"));
        if (seed != null) {
            section.row("Roll Seed", String.format(Locale.ROOT, "%016x", seed));
            rows++;
        }
        Long version = longNumber(getIgnoreCase(item, "version"));
        if (version != null) {
            section.row("Item Version", Long.toString(version));
            rows++;
        }
        if (rows > 0) {
            builder.addSection(section.build());
        }
    }

    private static String mysticRarityColor(String rarity) {
        return switch (rarity.toUpperCase(Locale.ROOT)) {
            case "COMMON" -> "#9aa6b8";
            case "UNCOMMON" -> "#54b978";
            case "RARE" -> "#4d83c7";
            case "EPIC" -> "#aa78e6";
            case "LEGENDARY" -> "#d3a83d";
            case "MYTHIC" -> "#cf565d";
            default -> null;
        };
    }

    private static String localId(String id) {
        int separator = id.indexOf(':');
        return separator >= 0 && separator + 1 < id.length() ? id.substring(separator + 1) : id;
    }

    private static String enumName(String value) {
        return ItemNames.prettify(value == null ? null : value.toLowerCase(Locale.ROOT));
    }

    private static String joinEnumNames(BsonArray values) {
        StringBuilder out = new StringBuilder();
        for (BsonValue value : values) {
            String text = scalarText(value);
            if (text == null) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(enumName(text));
        }
        return out.toString();
    }

    private record MysticAffix(String statistic, String displayName, boolean percentage) {
    }

    /**
     * SimpleEnchantments v1/v2 contract:
     * {@code Enchantments: {Version: 2, Values: {sharpness: 3}}}. Older builds
     * stored the enchantment map directly, so both shapes are accepted.
     */
    private static boolean readEnchantments(BsonDocument document, ItemViewBuilder builder) {
        BsonDocument values = document;
        BsonValue nested = getIgnoreCase(document, "Values");
        if (nested != null && nested.isDocument()) {
            values = nested.asDocument();
        }

        ItemViewSection.Builder section = ItemViewSection
                .builder("native:enchantments", "Enchantments")
                .placement(ItemViewSection.Placement.AFTER_STATISTICS)
                .accentColor("#A970FF");
        int rows = 0;
        for (Map.Entry<String, BsonValue> entry : values.entrySet()) {
            if ("version".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            Integer level = integer(entry.getValue());
            if (level == null || level <= 0) {
                continue;
            }
            section.row(ItemNames.prettify(entry.getKey()), "Level " + level);
            rows++;
        }
        if (rows == 0) {
            return false;
        }
        builder.addSection(section.build());
        builder.addTag("enchanted");
        return true;
    }

    /**
     * LuxReforge's stable stack contract. Classification and rolls are promoted
     * into the standard model; mechanics without a standard axis remain in a
     * dedicated section.
     */
    private static boolean readLuxReforge(BsonDocument document, ItemViewBuilder builder) {
        String rarityId = string(document, "rarity");
        String displayName = string(document, "name");
        String color = color(string(document, "color"));
        String tierId = string(document, "tier");
        String mutationId = string(document, "mut");

        boolean recognized = rarityId != null || displayName != null || tierId != null
                || mutationId != null || getIgnoreCase(document, "rolls") != null
                || getIgnoreCase(document, "stats") != null;
        if (!recognized) {
            return false;
        }

        if (rarityId != null || displayName != null) {
            builder.rarity(ItemClassification.builder()
                    .id(rarityId)
                    .displayName(displayName)
                    .color(color)
                    .build());
        }
        if (tierId != null) {
            builder.tier(ItemClassification.builder().id(tierId).build());
        }

        readLuxRolls(getIgnoreCase(document, "rolls"), builder);
        readLuxLegacyStats(getIgnoreCase(document, "stats"), builder);

        ItemViewSection.Builder section = ItemViewSection
                .builder("native:lux-reforge", "Lux Reforge")
                .placement(ItemViewSection.Placement.CUSTOM_MECHANICS)
                .accentColor(color);
        int rows = 0;
        if (mutationId != null) {
            section.row("Mutation", ItemNames.prettify(mutationId));
            rows++;
        }

        Integer masteryLevel = integer(getIgnoreCase(document, "mlv"));
        Double masteryXp = number(getIgnoreCase(document, "mxp"));
        if (masteryLevel != null && masteryLevel > 0) {
            String value = Integer.toString(masteryLevel);
            if (masteryXp != null && masteryXp > 0) {
                value += " (" + ItemNames.number(masteryXp) + " XP)";
            }
            section.row("Mastery", value);
            rows++;
        }

        BsonValue resonance = getIgnoreCase(document, "res");
        if (resonance != null && resonance.isArray() && !resonance.asArray().isEmpty()) {
            section.row("Resonance", joinStrings(resonance.asArray()));
            rows++;
        }

        if (rows > 0) {
            builder.addSection(section.build());
        }
        builder.addTag("lux-reforged");
        return true;
    }

    private static void readLuxRolls(BsonValue value, ItemViewBuilder builder) {
        if (value == null || !value.isArray()) {
            return;
        }
        for (BsonValue entry : value.asArray()) {
            if (!entry.isDocument()) {
                continue;
            }
            BsonDocument roll = entry.asDocument();
            String statId = string(roll, "id");
            Double amount = number(getIgnoreCase(roll, "v"));
            if (statId == null || amount == null || amount == 0) {
                continue;
            }
            String type = string(roll, "t");
            boolean percentage = type != null
                    && type.toLowerCase(Locale.ROOT).startsWith("pct");
            builder.addModifier(statId, amount, percentage, "LuxReforge");
        }
    }

    private static void readLuxLegacyStats(BsonValue value, ItemViewBuilder builder) {
        if (value == null || !value.isDocument()) {
            return;
        }
        for (Map.Entry<String, BsonValue> entry : value.asDocument().entrySet()) {
            Double amount = number(entry.getValue());
            if (amount != null && amount != 0) {
                builder.addModifier(entry.getKey(), amount, false, "LuxReforge");
            }
        }
    }

    private static String joinStrings(BsonArray values) {
        StringBuilder out = new StringBuilder();
        for (BsonValue value : values) {
            String text = scalarText(value);
            if (text == null) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(ItemNames.prettify(text));
        }
        return out.toString();
    }

    private static BsonValue getIgnoreCase(BsonDocument document, String key) {
        BsonValue exact = document.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, BsonValue> entry : document.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String string(BsonDocument document, String key) {
        return scalarText(getIgnoreCase(document, key));
    }

    private static String scalarText(BsonValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            if (value.isString()) {
                String text = value.asString().getValue();
                return text == null || text.isBlank() ? null : text;
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
        } catch (Throwable ignored) {
            // A malformed custom document simply contributes no structured data.
        }
        return null;
    }

    private static Integer integer(BsonValue value) {
        Double number = number(value);
        return number == null ? null : (int) Math.round(number);
    }

    private static Long longNumber(BsonValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            if (value.isInt32()) {
                return (long) value.asInt32().getValue();
            }
            if (value.isInt64()) {
                return value.asInt64().getValue();
            }
            if (value.isDouble()) {
                return Math.round(value.asDouble().getValue());
            }
            if (value.isString()) {
                return Long.parseLong(value.asString().getValue());
            }
        } catch (Throwable ignored) {
            // Not an integer.
        }
        return null;
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
                return Double.parseDouble(value.asString().getValue());
            }
        } catch (Throwable ignored) {
            // Not numeric.
        }
        return null;
    }

    private static boolean bool(BsonValue value, boolean fallback) {
        if (value == null || value.isNull()) {
            return fallback;
        }
        try {
            if (value.isBoolean()) {
                return value.asBoolean().getValue();
            }
            if (value.isString()) {
                return Boolean.parseBoolean(value.asString().getValue());
            }
        } catch (Throwable ignored) {
            // Not a boolean.
        }
        return fallback;
    }

    private static boolean containsIgnoreCase(BsonValue value, String expected) {
        if (value == null || !value.isArray()) {
            return false;
        }
        for (BsonValue entry : value.asArray()) {
            String text = scalarText(entry);
            if (text != null && expected.equalsIgnoreCase(text)) {
                return true;
            }
        }
        return false;
    }

    private static String color(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.startsWith("#") ? value : "#" + value;
    }
}
