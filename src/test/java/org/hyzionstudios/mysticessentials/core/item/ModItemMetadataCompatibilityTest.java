package org.hyzionstudios.mysticessentials.core.item;

import java.util.List;

import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.hyzionstudios.mysticessentials.api.item.ItemViewData;
import org.hyzionstudios.mysticessentials.api.item.RichText;
import org.hyzionstudios.mysticessentials.core.message.MysticText;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;

/** Dependency-free compatibility checks, executed by the Gradle verification task. */
public final class ModItemMetadataCompatibilityTest {

    private ModItemMetadataCompatibilityTest() {
    }

    public static void main(String[] args) {
        readsSimpleEnchantmentsV2();
        readsLuxReforgeContract();
        readsMysticRpgRerolledGear();
        removesMysticRpgLoreAfterPromotion();
        preservesCompositeTranslatedNames();
        rejectsMalformedMysticRpgPayload();
        rejectsUnknownDocuments();
    }

    private static void readsSimpleEnchantmentsV2() {
        BsonDocument values = new BsonDocument()
                .append("sharpness", new BsonInt32(3))
                .append("life_leech", new BsonInt32(2));
        BsonDocument document = new BsonDocument()
                .append("Version", new BsonInt32(2))
                .append("Values", values);

        var builder = ItemViewData.builder("Weapon_Test");
        require(ModItemMetadataInspector.inspect("Enchantments", document, builder),
                "SimpleEnchantments document was not recognized");
        ItemViewData view = builder.build();

        require(view.customSections().size() == 1, "enchantment section missing");
        require(view.customSections().getFirst().rows().size() == 2,
                "enchantment rows were not preserved");
        require("Sharpness".equals(view.customSections().getFirst().rows().getFirst().label().plain()),
                "enchantment id was not prettified");
        require(view.tags().contains("enchanted"), "enchanted tag missing");
    }

    private static void readsLuxReforgeContract() {
        BsonDocument percentageRoll = new BsonDocument()
                .append("id", new BsonString("critical_chance"))
                .append("v", new BsonDouble(8.5))
                .append("t", new BsonString("pct"));
        BsonDocument flatRoll = new BsonDocument()
                .append("id", new BsonString("health"))
                .append("v", new BsonDouble(12))
                .append("t", new BsonString("flat"));
        BsonDocument document = new BsonDocument()
                .append("rarity", new BsonString("tempest"))
                .append("name", new BsonString("Tempest"))
                .append("color", new BsonString("44AAFF"))
                .append("tier", new BsonString("ascendant"))
                .append("mut", new BsonString("volatile"))
                .append("mlv", new BsonInt32(4))
                .append("mxp", new BsonDouble(125.5))
                .append("res", new BsonArray(List.of(new BsonString("storm"))))
                .append("rolls", new BsonArray(List.of(percentageRoll, flatRoll)));

        var builder = ItemViewData.builder("Weapon_Test");
        require(ModItemMetadataInspector.inspect("LuxReforge", document, builder),
                "LuxReforge document was not recognized");
        ItemViewData view = builder.build();

        require("Tempest".equals(view.classification().rarity().orElseThrow().displayName()),
                "Lux rarity missing");
        require("#44AAFF".equals(view.classification().rarity().orElseThrow().color()),
                "Lux rarity color missing");
        require("Ascendant".equals(view.classification().tier().orElseThrow().displayName()),
                "Lux tier missing");
        require(view.modifiers().size() == 2, "Lux rolls missing");
        require(view.modifiers().getFirst().percentage(), "percentage roll type lost");
        require(view.customSections().size() == 1, "Lux mechanics section missing");
        require(view.tags().contains("lux-reforged"), "Lux tag missing");
    }

    private static void readsMysticRpgRerolledGear() {
        BsonDocument stormforged = new BsonDocument()
                .append("affix", new BsonString("mysticrpg:stormforged"))
                .append("kind", new BsonString("PREFIX"))
                .append("tier", new BsonInt32(3))
                .append("value", new BsonDouble(42))
                .append("locked", BsonBoolean.FALSE);
        BsonDocument precision = new BsonDocument()
                .append("affix", new BsonString("mysticrpg:of_precision"))
                .append("kind", new BsonString("SUFFIX"))
                .append("tier", new BsonInt32(2))
                .append("value", new BsonDouble(5.5))
                .append("locked", BsonBoolean.TRUE);
        BsonDocument item = new BsonDocument()
                .append("instance", new BsonString("00000000-0000-4000-8000-000000000001"))
                .append("template", new BsonString("mysticrpg:weapon_spear_cobalt"))
                .append("seed", new BsonInt64(0x1234ABCDEFL))
                .append("owner", new BsonString("00000000-0000-4000-8000-000000000002"))
                .append("binding", new BsonString("BOUND_TO_PLAYER"))
                .append("states", new BsonArray(List.of(new BsonString("BOUND"))))
                .append("rarity", new BsonString("RARE"))
                .append("quality", new BsonString("SUPERIOR"))
                .append("ilvl", new BsonInt32(40))
                .append("affixes", new BsonArray(List.of(stormforged, precision)))
                .append("sockets", new BsonArray())
                .append("skills", new BsonArray())
                .append("durability", new BsonInt32(87))
                .append("maxDurability", new BsonInt32(100))
                .append("version", new BsonInt64(2))
                .append("source", new BsonString("LOOT"));
        BsonDocument envelope = new BsonDocument()
                .append("v", new BsonInt32(1))
                .append("item", item);

        var builder = ItemViewData.builder("Weapon_Spear_Cobalt");
        require(ModItemMetadataInspector.inspect("mysticrpg:gear",
                        new BsonString(envelope.toJson()), builder),
                "MysticRPG gear stamp was not recognized");
        ItemViewData view = builder.build();

        require("Rare".equals(view.classification().rarity().orElseThrow().displayName()),
                "MysticRPG rarity missing");
        require("#4d83c7".equals(view.classification().rarity().orElseThrow().color()),
                "MysticRPG rarity color missing");
        require("Superior".equals(view.classification().quality().orElseThrow().displayName()),
                "MysticRPG quality missing");
        require(view.itemLevel().orElseThrow() == 40, "MysticRPG item level missing");
        require(view.modifiers().size() == 2, "MysticRPG affixes missing");
        require("Physical Damage".equals(view.modifiers().getFirst().name().plain()),
                "MysticRPG affix target missing");
        require(!view.modifiers().getFirst().percentage(), "flat MysticRPG affix became percent");
        require(view.modifiers().get(1).percentage(), "MysticRPG percentage phase lost");
        require(view.modifiers().get(1).source().contains("Locked"),
                "MysticRPG locked affix state missing");
        require(view.durability().orElseThrow().current() == 87,
                "MysticRPG durability missing");
        require(view.binding().orElseThrow().bound(), "MysticRPG binding missing");
        require(!view.binding().orElseThrow().tradable(), "bound MysticRPG item became tradable");
        require(view.customSections().size() == 1, "MysticRPG mechanics section missing");
        require(view.tags().contains("mysticrpg-gear"), "MysticRPG tag missing");
    }

    private static void rejectsMalformedMysticRpgPayload() {
        var builder = ItemViewData.builder("Weapon_Test");
        require(!ModItemMetadataInspector.inspect("mysticrpg:gear",
                        new BsonString("{not-json"), builder),
                "malformed MysticRPG data must remain in the technical dump");
    }

    private static void removesMysticRpgLoreAfterPromotion() {
        String mysticLore = "Rare Superior  •  Item Level 40\n"
                + "+42 Physical Damage\nSeed 0000001234abcdef";
        String composed = "Forged into legend as Cobalt Spear.\n\n" + mysticLore;
        require("Forged into legend as Cobalt Spear.".equals(
                        NativeItemInspector.withoutMysticRpgLore(composed, mysticLore)),
                "promoted MysticRPG lore was duplicated in the description");
    }

    private static void preservesCompositeTranslatedNames() {
        String itemKey = "MajorDungeons.items.Armor_DarkSilver_Charged_Head.name";
        Message baseName = Message.translation(itemKey)
                .param("material", Message.translation("server.material.cobalt.name"));
        Message composed = Message.raw("Vigorous ").insert(baseName);
        RichText name = NativeItemInspector.formattedRichText(composed.getFormattedMessage());

        require(name.hasTranslations(), "composite item name lost its translation segment");
        require(!name.isTranslated(), "composite name was mistaken for a single translation");
        require(name.markup().contains("<lang:" + itemKey
                        + "|material=@server.material.cobalt.name>"),
                "composite item name lost its translation parameters");

        ItemViewData view = ItemViewData.builder("Armor_DarkSilver_Charged_Head")
                .displayName(name)
                .build();
        require("Vigorous Armor DarkSilver Charged Head".equals(view.plainName()),
                "plain sinks leaked the raw lang token: " + view.plainName());

        Message rendered = MysticText.parse("<link:/itemview kp83><#54b978>["
                + name.markup() + "]</#></link>");
        FormattedMessage translated = findTranslation(rendered.getFormattedMessage(), itemKey);
        require(translated != null, "chat rendering flattened the translated item name");
        require("/itemview kp83".equals(translated.link),
                "translated item name lost its item-view link");
        require(translated.messageParams != null
                        && translated.messageParams.containsKey("material"),
                "translated item name lost its client-side material parameter");
    }

    private static FormattedMessage findTranslation(FormattedMessage node, String key) {
        if (node == null) {
            return null;
        }
        if (key.equals(node.messageId)) {
            return node;
        }
        if (node.children != null) {
            for (FormattedMessage child : node.children) {
                FormattedMessage found = findTranslation(child, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void rejectsUnknownDocuments() {
        var builder = ItemViewData.builder("Unknown_Test");
        require(!ModItemMetadataInspector.inspect("OtherMod", new BsonDocument(), builder),
                "unknown metadata must remain available to the technical dump");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
