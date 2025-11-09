package com.alphine.mysticessentials.inv;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public final class InventoryIO {
    private InventoryIO(){}

    /** Capture target inventory -> payload with proper registry provider */
    public static Map<String,Object> captureToPayload(ServerPlayer p){
        HolderLookup.Provider provider = p.level().registryAccess();
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("main",  writeRange(p.getInventory(), 0, 36, provider));
        payload.put("armor", writeArmor(p.getInventory(), provider));
        payload.put("offhand", writeOne(p.getInventory().offhand.get(0), provider));
        return payload;
    }

    /** Build stacks (0..40) from payload using a provider. */
    public static ItemStack[] stacksFromPayload(HolderLookup.Provider provider, Map<String,Object> payload){
        ItemStack[] out = new ItemStack[41];
        Arrays.fill(out, ItemStack.EMPTY);

        Map<String,Object> main  = map(payload.get("main"));
        Map<String,Object> armor = map(payload.get("armor"));
        Object offhand           = payload.get("offhand");

        if(main!=null){
            for(int i=0;i<36;i++) out[i] = readOne(main.get(String.valueOf(i)), provider);
        }
        if(armor!=null){
            for(int i=0;i<4;i++) out[36+i] = readOne(armor.get(String.valueOf(i)), provider);
        }
        out[40] = readOne(offhand, provider);
        return out;
    }

    /** Persist edited stacks back to payload format (no provider needed for writing JSON strings). */
    public static Map<String,Object> payloadFromStacks(HolderLookup.Provider provider, ItemStack[] stacks){
        Map<String,Object> payload = new LinkedHashMap<>();
        Map<String,Object> main = new LinkedHashMap<>();
        for(int i=0;i<36;i++) main.put(String.valueOf(i), writeOne(stacks[i], provider));
        Map<String,Object> armor = new LinkedHashMap<>();
        for(int i=0;i<4;i++) armor.put(String.valueOf(i), writeOne(stacks[36+i], provider));
        Object off = writeOne(stacks[40], provider);
        payload.put("main", main);
        payload.put("armor", armor);
        payload.put("offhand", off);
        return payload;
    }

    // ---- helpers ----
    private static Map<String,Object> writeRange(Inventory inv, int start, int count, HolderLookup.Provider provider){
        Map<String,Object> m = new LinkedHashMap<>();
        for(int i=0;i<count;i++) m.put(String.valueOf(i), writeOne(inv.getItem(start+i), provider));
        return m;
    }
    private static Map<String,Object> writeArmor(Inventory inv, HolderLookup.Provider provider){
        Map<String,Object> m = new LinkedHashMap<>();
        for(int i=0;i<4;i++) m.put(String.valueOf(i), writeOne(inv.armor.get(i), provider));
        return m;
    }

    /** 1.21: save requires Provider first. Return NBT JSON string or null for empty. */
    private static Object writeOne(ItemStack s, HolderLookup.Provider provider){
        if(s==null || s.isEmpty()) return null;
        CompoundTag tag = new CompoundTag();
        s.save(provider, tag);                 // <-- FIX
        return tag.toString();
    }

    /** 1.21: parse requires Provider. */
    private static ItemStack readOne(Object raw, HolderLookup.Provider provider){
        if(!(raw instanceof String str) || str.isBlank()) return ItemStack.EMPTY;
        try {
            CompoundTag tag = TagParser.parseTag(str);
            // 1.21 method:
            return ItemStack.parse(provider, tag).orElse(ItemStack.EMPTY);
        } catch (Exception ignored){
            return ItemStack.EMPTY;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object o){ return (o instanceof Map)? (Map<String,Object>)o : null; }
}
