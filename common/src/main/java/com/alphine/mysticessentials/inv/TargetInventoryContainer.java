package com.alphine.mysticessentials.inv;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Chest-like Container that mirrors a target player's inventory:
 * Slots 0..35  -> target main inventory (0..35)
 * Slots 36..39 -> target armor (0: boots, 1: leggings, 2: chest, 3: helmet)
 * Slot  40     -> target offhand
 * Slots 41..53 -> filler (read-only, EMPTY)
 *
 * Editing is controlled by the 'editable' flag.
 */
public class TargetInventoryContainer implements Container {

    private final Player target;
    private final boolean editable;

    public static final int SIZE = 54;

    public TargetInventoryContainer(Player target, boolean editable) {
        this.target = Objects.requireNonNull(target);
        this.editable = editable;
    }

    @Override public int getContainerSize() { return SIZE; }

    @Override
    public boolean isEmpty() {
        // quick check
        return target.getInventory().isEmpty() &&
                target.getOffhandItem().isEmpty() &&
                target.getInventory().armor.get(0).isEmpty() &&
                target.getInventory().armor.get(1).isEmpty() &&
                target.getInventory().armor.get(2).isEmpty() &&
                target.getInventory().armor.get(3).isEmpty();
    }

    private boolean isFiller(int slot) { return slot >= 41 && slot < SIZE; }

    private ItemStack getMapped(int slot) {
        Inventory inv = target.getInventory();
        if (slot >= 0 && slot <= 35) return inv.getItem(slot);                // main
        if (slot >= 36 && slot <= 39) return inv.armor.get(slot - 36);        // armor
        if (slot == 40) return target.getOffhandItem();                       // offhand
        return ItemStack.EMPTY;                                               // filler
    }

    private void setMapped(int slot, ItemStack stack) {
        Inventory inv = target.getInventory();
        if (slot >= 0 && slot <= 35) { inv.setItem(slot, stack); return; }
        if (slot >= 36 && slot <= 39) { inv.armor.set(slot - 36, stack); return; }
        if (slot == 40) { target.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, stack); return; }
    }

    @Override
    public ItemStack getItem(int slot) {
        return getMapped(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (!editable || isFiller(slot)) return ItemStack.EMPTY;
        ItemStack cur = getMapped(slot);
        if (cur.isEmpty()) return ItemStack.EMPTY;
        ItemStack split = cur.split(amount);
        setChanged();
        return split;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (!editable || isFiller(slot)) return ItemStack.EMPTY;
        ItemStack cur = getMapped(slot);
        if (cur.isEmpty()) return ItemStack.EMPTY;
        ItemStack ret = cur.copy();
        setMapped(slot, ItemStack.EMPTY);
        setChanged();
        return ret;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!editable || isFiller(slot)) return;
        setMapped(slot, stack);
        setChanged();
    }

    @Override public void setChanged() {
        // mark target inventory dirty so it syncs
        target.getInventory().setChanged();
        target.containerMenu.broadcastChanges();
    }

    @Override public boolean stillValid(Player viewer) { return viewer.isAlive() && target.isAlive(); }

    @Override public void startOpen(Player viewer) { }

    @Override public void stopOpen(Player viewer) { }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return editable && !isFiller(slot);
    }

    @Override public int getMaxStackSize() { return 64; }

    @Override public void clearContent() {
        if (!editable) return;
        for (int i = 0; i < SIZE; i++) if (!isFiller(i)) setMapped(i, ItemStack.EMPTY);
        setChanged();
    }
}
