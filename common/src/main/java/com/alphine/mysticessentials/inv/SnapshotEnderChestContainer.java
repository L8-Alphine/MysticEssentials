package com.alphine.mysticessentials.inv;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.Objects;

/**
 * Simple read-only chest-sized container (27 slots) used for
 * ender chest snapshots shared via chat (<ec> tag).
 */
public final class SnapshotEnderChestContainer implements Container {

    public static final int SIZE = 27;

    private final ItemStack[] slots;

    public SnapshotEnderChestContainer(ItemStack[] initial) {
        Objects.requireNonNull(initial, "initial");
        this.slots = new ItemStack[SIZE];
        Arrays.fill(this.slots, ItemStack.EMPTY);
        int len = Math.min(initial.length, SIZE);
        for (int i = 0; i < len; i++) {
            this.slots[i] = initial[i] == null ? ItemStack.EMPTY : initial[i].copy();
        }
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : slots) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return (slot >= 0 && slot < SIZE) ? slots[slot] : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        // read-only
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        // read-only
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        // read-only
    }

    @Override
    public void setChanged() {
        // no-op (snapshot)
    }

    @Override
    public boolean stillValid(Player viewer) {
        return viewer.isAlive();
    }

    @Override
    public void startOpen(Player viewer) {}

    @Override
    public void stopOpen(Player viewer) {}

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return false; // read-only
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public void clearContent() {
        // read-only
    }
}
