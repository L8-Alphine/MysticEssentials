package com.alphine.mysticessentials.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

public final class StackTextUtil {
    private StackTextUtil() {}

    public static ItemStack setName(ItemStack stack, Component name) {
        if (stack == null || stack.isEmpty()) return stack;
        stack.set(DataComponents.CUSTOM_NAME, name);
        return stack;
    }

    public static ItemStack setLore(ItemStack stack, List<Component> lines) {
        if (stack == null || stack.isEmpty()) return stack;
        if (lines == null) lines = List.of();
        // ensure mutable copy
        List<Component> copy = new ArrayList<>(lines);
        stack.set(DataComponents.LORE, new ItemLore(copy));
        return stack;
    }
}
