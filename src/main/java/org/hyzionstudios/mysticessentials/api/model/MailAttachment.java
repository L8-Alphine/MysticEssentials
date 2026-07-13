package org.hyzionstudios.mysticessentials.api.model;

/**
 * A single item stack escrowed on a mail as a reward. This is the
 * storage-provider-neutral representation, mirroring the vault's item model: the
 * item's full BSON metadata is kept as its JSON string (see
 * {@code stack.getMetadata().toJson()}) so durability, custom model data, and any
 * NBT-equivalent state survive the round trip through JSON or SQL and back into a
 * live {@code ItemStack} when the recipient claims it.
 *
 * <p>Kept in {@code api.model} as plain data so the API package never depends on
 * the {@code modules} package; the Mail module owns the live-item conversion
 * (see {@code MailItemCodec}).</p>
 */
public final class MailAttachment {

    public String itemId;
    public int quantity = 1;
    public double durability;
    public double maxDurability;
    /** JSON form of the item's BSON metadata; {@code null} when the item has none. */
    public String metadata;

    public MailAttachment() {
    }

    public MailAttachment(String itemId, int quantity, double durability,
            double maxDurability, String metadata) {
        this.itemId = itemId;
        this.quantity = quantity;
        this.durability = durability;
        this.maxDurability = maxDurability;
        this.metadata = metadata;
    }

    public MailAttachment copy() {
        return new MailAttachment(itemId, quantity, durability, maxDurability, metadata);
    }
}
