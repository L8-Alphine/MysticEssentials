package com.alphine.mysticessentials.storage;

import com.google.gson.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class KitStore {
    public static final class Kit {
        public String name;
        public long cooldownMillis; // 0 = no cooldown, -1 ignored when oneTime=true
        public boolean oneTime;     // true = can be claimed once per player
        public List<String> itemsB64 = new ArrayList<>(); // Base64 of binary NBT per item
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Kit> kits = new HashMap<>(); // name (lc) -> kits

    public KitStore(Path cfgDir){
        this.file = cfgDir.resolve("kits.json");
        load();
    }

    public synchronized List<String> names(){
        return kits.keySet().stream().sorted().toList();
    }

    public synchronized Optional<Kit> get(String name){
        return Optional.ofNullable(kits.get(name.toLowerCase(Locale.ROOT)));
    }

    public synchronized void put(Kit k){
        kits.put(k.name.toLowerCase(Locale.ROOT), k);
        save();
    }

    public synchronized boolean delete(String name){
        boolean ok = kits.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (ok) save();
        return ok;
    }

    public static String stackToB64(ItemStack stack, HolderLookup.Provider provider) {
        try (var baos = new java.io.ByteArrayOutputStream()) {
            CompoundTag tag = (CompoundTag) stack.save(provider);
            NbtIo.writeCompressed(tag, baos);
            return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    public static ItemStack b64ToStack(String b64, HolderLookup.Provider provider) {
        try (var bais = new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(b64))) {
            // some 1.21 jars require the NbtAccounter overload
            CompoundTag tag = NbtIo.readCompressed(bais, NbtAccounter.unlimitedHeap());
            return ItemStack.parseOptional(provider, tag);     // 1.21+: replaces ItemStack.of(tag)
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private void load(){
        try {
            Files.createDirectories(file.getParent());
            if(!Files.exists(file)){ save(); return; }
            try (Reader r = Files.newBufferedReader(file)) {
                Kit[] arr = gson.fromJson(r, Kit[].class);
                kits.clear();
                if (arr != null) for (var k : arr) kits.put(k.name.toLowerCase(Locale.ROOT), k);
            }
        } catch (Exception ignored) {}
    }

    private void save(){
        try (Writer w = Files.newBufferedWriter(file)) {
            gson.toJson(kits.values(), w);
        } catch (Exception ignored) {}
    }
}
