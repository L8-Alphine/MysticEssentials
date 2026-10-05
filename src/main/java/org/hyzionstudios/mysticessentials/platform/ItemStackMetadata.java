package org.hyzionstudios.mysticessentials.platform;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Reads an {@link ItemStack}'s complete BSON metadata without the deprecated
 * {@code ItemStack#getMetadata()}. The stack is encoded through
 * {@link ItemStack#CODEC} -- the engine's own persistence path, which writes the
 * metadata document under its {@code Metadata} key -- and that field is copied
 * out, so lossless round trips and full metadata enumeration keep working.
 */
public final class ItemStackMetadata {

    private static final String METADATA_KEY = "Metadata";

    private ItemStackMetadata() {
    }

    /** @return a private copy of the stack's metadata, or {@code null} when it has none. */
    public static BsonDocument read(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        BsonValue metadata = ItemStack.CODEC.encode(stack, new ExtraInfo()).get(METADATA_KEY);
        return metadata != null && metadata.isDocument() ? metadata.asDocument().clone() : null;
    }

    /** @return the stack's metadata as JSON, or {@code null} when it has none or it is empty. */
    public static String toJson(ItemStack stack) {
        BsonDocument metadata = read(stack);
        return metadata == null || metadata.isEmpty() ? null : metadata.toJson();
    }
}
