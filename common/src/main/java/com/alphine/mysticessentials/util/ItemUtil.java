package com.alphine.mysticessentials.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ItemUtil {
    private ItemUtil() {}

    /**
     * Create an ItemStack from a namespaced id like "minecraft:barrel".
     * If invalid/missing, returns AIR stack.
     */
    public static ItemStack fromId(String namespacedId, int amount) {
        if (amount < 1) amount = 1;

        ResourceLocation rl = parseRL(namespacedId);
        if (rl == null) return ItemStack.EMPTY;

        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == null) return ItemStack.EMPTY;

        return new ItemStack(item, amount);
    }

    public static ResourceLocation parseRL(String id) {
        if (id == null || id.isBlank()) return null;
        // Accept "barrel" as shorthand for minecraft:barrel if you want:
        String fixed = id.contains(":") ? id : ("minecraft:" + id);
        return ResourceLocation.tryParse(fixed);
    }

    public static String getItemId(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "minecraft:air";
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
