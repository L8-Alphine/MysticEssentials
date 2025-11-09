package com.alphine.mysticessentials.inv;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

public final class SnapshotInventoryContainer implements Container {
    public static final int SIZE = 54; // 6 rows, filler on 41..53
    private final boolean editable;
    private final ItemStack[] slots; // 0..40 used
    private final Consumer<ItemStack[]> onSave; // may be null

    public SnapshotInventoryContainer(ItemStack[] initial, boolean editable, Consumer<ItemStack[]> onSave){
        this.slots = Arrays.copyOf(Objects.requireNonNull(initial), 41);
        this.editable = editable;
        this.onSave = onSave;
    }

    @Override public int getContainerSize() { return SIZE; }
    @Override public boolean isEmpty() {
        for(int i=0;i<41;i++) if(!slots[i].isEmpty()) return false;
        return true;
    }
    private boolean isFiller(int slot){ return slot>=41 && slot<SIZE; }

    @Override public ItemStack getItem(int slot){ return (slot>=0 && slot<41)? slots[slot] : ItemStack.EMPTY; }

    @Override public ItemStack removeItem(int slot, int amount){
        if(!editable || isFiller(slot)) return ItemStack.EMPTY;
        ItemStack cur = getItem(slot);
        if(cur.isEmpty()) return ItemStack.EMPTY;
        ItemStack split = cur.split(amount);
        setChanged();
        return split;
    }

    @Override public ItemStack removeItemNoUpdate(int slot){
        if(!editable || isFiller(slot)) return ItemStack.EMPTY;
        ItemStack cur = getItem(slot);
        if(cur.isEmpty()) return ItemStack.EMPTY;
        ItemStack ret = cur.copy();
        slots[slot] = ItemStack.EMPTY;
        setChanged();
        return ret;
    }

    @Override public void setItem(int slot, ItemStack stack){
        if(!editable || isFiller(slot)) return;
        if(slot>=0 && slot<41){
            slots[slot] = stack.copy();
            setChanged();
        }
    }

    @Override public void setChanged(){
        if(onSave!=null) onSave.accept(Arrays.copyOf(slots, slots.length));
    }

    @Override public boolean stillValid(Player viewer){ return viewer.isAlive(); }
    @Override public void startOpen(Player viewer) {}
    @Override public void stopOpen(Player viewer) {}
    @Override public boolean canPlaceItem(int slot, ItemStack stack){ return editable && !isFiller(slot); }
    @Override public int getMaxStackSize(){ return 64; }
    @Override public void clearContent(){
        if(!editable) return;
        for(int i=0;i<41;i++) slots[i] = ItemStack.EMPTY;
        setChanged();
    }
}
