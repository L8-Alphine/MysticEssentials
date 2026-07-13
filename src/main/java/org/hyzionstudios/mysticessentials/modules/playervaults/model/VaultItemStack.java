package org.hyzionstudios.mysticessentials.modules.playervaults.model;

/**
 * A single stored item occupying one slot of a vault. This is the
 * storage-provider-neutral representation: the item's full BSON metadata is kept
 * as its JSON string (see {@code stack.getMetadata().toJson()}) so any item
 * state — durability, custom model data, enchants, NBT-equivalents — survives
 * the round trip through JSON or SQL and back into a live {@code ItemStack}.
 *
 * <p>Slots are absolute indices into the vault's flat item array
 * ({@code row * slotsPerRow + column}); only occupied slots are recorded, so an
 * empty vault serializes to an empty list.</p>
 */
public final class VaultItemStack {

    public int slot;
    public String itemId;
    public int quantity = 1;
    public double durability;
    public double maxDurability;
    /** JSON form of the item's BSON metadata; {@code null} when the item has none. */
    public String metadata;

    public VaultItemStack() {
    }

    public VaultItemStack(int slot, String itemId, int quantity, double durability,
            double maxDurability, String metadata) {
        this.slot = slot;
        this.itemId = itemId;
        this.quantity = quantity;
        this.durability = durability;
        this.maxDurability = maxDurability;
        this.metadata = metadata;
    }

    public VaultItemStack copy() {
        return new VaultItemStack(slot, itemId, quantity, durability, maxDurability, metadata);
    }
}
