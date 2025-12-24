package com.alphine.mysticessentials.npc.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;

public final class NpcItemCodec {

    private NpcItemCodec() {}

    public static String encode(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) return "";
        CompoundTag tag = new CompoundTag();
        stack.save(registries, tag);
        return tag.toString();
    }

    public static ItemStack decode(String snbt, HolderLookup.Provider registries) {
        if (snbt == null || snbt.isBlank()) return ItemStack.EMPTY;
        try {
            CompoundTag tag = TagParser.parseTag(snbt);
            return ItemStack.parse(registries, tag).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }
}
