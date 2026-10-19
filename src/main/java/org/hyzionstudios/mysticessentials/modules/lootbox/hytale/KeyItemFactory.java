package org.hyzionstudios.mysticessentials.modules.lootbox.hytale;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.KeyMarker;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Builds and reads physical key items. A key is its definition's base item with
 * a per-stack {@link ItemDisplayMetadata} (name, and the lore lines joined into
 * the single description the engine supports) plus the signed
 * {@link KeyMarker}. The serial in the marker makes every key's metadata
 * distinct, so serialized keys never stack.
 */
public final class KeyItemFactory {

    private KeyItemFactory() {
    }

    public static ItemStack create(KeyDraft.Published definition, KeyMarker marker) {
        KeyDraft spec = definition.spec;
        Message name = MysticText.parse(spec.name == null ? spec.id : spec.name);
        Message description = spec.lore == null || spec.lore.isEmpty() ? null
                : MysticText.parse(String.join("\n", spec.lore));
        return new ItemStack(spec.baseItemId, 1)
                .withMetadata(ItemDisplayMetadata.KEYED_CODEC, new ItemDisplayMetadata(name, description))
                .withMetadata(KeyMarker.METADATA_KEY, toBson(marker));
    }

    /** @return the stack's marker, or {@code null} when it carries none (any ordinary item). */
    public static KeyMarker read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        BsonDocument doc;
        try {
            doc = stack.getFromMetadataOrNull(KeyMarker.METADATA_KEY, Codec.BSON_DOCUMENT);
        } catch (RuntimeException e) {
            return null;
        }
        if (doc == null) {
            return null;
        }
        return new KeyMarker(string(doc, "k"), integer(doc, "v"), string(doc, "s"), integer(doc, "kid"),
                string(doc, "h"));
    }

    static BsonDocument toBson(KeyMarker marker) {
        return new BsonDocument()
                .append("k", new BsonString(marker.keyId()))
                .append("v", new BsonInt32(marker.version()))
                .append("s", new BsonString(marker.serial()))
                .append("kid", new BsonInt32(marker.secretId()))
                .append("h", new BsonString(marker.signature()));
    }

    private static String string(BsonDocument doc, String key) {
        BsonValue value = doc.get(key);
        return value != null && value.isString() ? value.asString().getValue() : null;
    }

    private static int integer(BsonDocument doc, String key) {
        BsonValue value = doc.get(key);
        return value != null && value.isNumber() ? value.asNumber().intValue() : 0;
    }
}
