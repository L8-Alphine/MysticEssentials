package org.hyzionstudios.mysticessentials.modules.mail;

import org.hyzionstudios.mysticessentials.api.model.MailAttachment;

import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Converts between live Hytale {@link ItemStack}s and the persistence-neutral
 * {@link MailAttachment}. Full BSON metadata is preserved as its JSON string on
 * the way out and rebuilt on the way in, so no item state is lost or fabricated —
 * a prerequisite for dupe-free escrow (mirrors the vault's {@code VaultItemCodec}).
 */
final class MailItemCodec {

    private MailItemCodec() {
    }

    /** Serializes an occupied item stack; returns {@code null} for empty stacks. */
    static MailAttachment toStored(ItemStack stack, int quantity) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        var metadata = stack.getMetadata();
        String json = metadata == null || metadata.isEmpty() ? null : metadata.toJson();
        return new MailAttachment(stack.getItemId(), Math.max(1, quantity),
                stack.getDurability(), stack.getMaxDurability(), json);
    }

    /** Rebuilds a live item from a stored attachment, restoring full metadata. */
    static ItemStack toLive(MailAttachment stored) {
        org.bson.BsonDocument metadata = stored.metadata == null || stored.metadata.isBlank()
                ? new org.bson.BsonDocument()
                : org.bson.BsonDocument.parse(stored.metadata);
        return new ItemStack(stored.itemId, Math.max(1, stored.quantity),
                stored.durability, stored.maxDurability, metadata);
    }
}
