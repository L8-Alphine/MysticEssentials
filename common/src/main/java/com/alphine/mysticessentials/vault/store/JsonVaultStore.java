package com.alphine.mysticessentials.vault.store;

import com.alphine.mysticessentials.vault.VaultMeta;
import com.alphine.mysticessentials.vault.VaultProfile;
import com.alphine.mysticessentials.vault.VaultStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Per-player JSON store:
 *   <configDir>/vaults/<uuid>.json
 *
 * Items stored as SNBT per-slot (CompoundTag SNBT):
 *   - null => empty
 *   - string => stack tag SNBT
 *
 * NOTE: 1.21+ requires a HolderLookup.Provider (registry access) to save/parse ItemStacks.
 */
public final class JsonVaultStore implements VaultStore {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private final Path vaultDir;

    // REQUIRED for 1.21+ ItemStack codec
    private volatile HolderLookup.Provider registries;

    public JsonVaultStore(Path configDir) {
        this.vaultDir = configDir.resolve("vaults");
        try { Files.createDirectories(vaultDir); }
        catch (IOException e) { throw new RuntimeException("[MysticEssentials] Failed to create vaults directory: " + vaultDir, e); }
    }

    /** Call once on startup: store.setRegistryAccess(server.registryAccess()) */
    public void setRegistryAccess(HolderLookup.Provider provider) {
        this.registries = provider;
    }

    @Override
    public VaultProfile load(UUID playerId) {
        Path file = fileFor(playerId);
        if (!Files.exists(file)) return new VaultProfile(playerId);

        try (Reader r = Files.newBufferedReader(file)) {
            JsonModel model = GSON.fromJson(r, JsonModel.class);
            if (model == null) return new VaultProfile(playerId);

            VaultProfile p = new VaultProfile(playerId);

            // meta
            if (model.meta != null) {
                for (Map.Entry<String, JsonMeta> e : model.meta.entrySet()) {
                    int idx = parseIndex(e.getKey());
                    if (idx <= 0) continue;

                    JsonMeta jm = e.getValue();
                    if (jm == null) continue;

                    VaultMeta vm = new VaultMeta();
                    vm.customBaseName = jm.name;
                    vm.displayItemId = jm.item;

                    p.metaByIndex.put(idx, vm);
                }
            }

            // items
            if (model.items != null) {
                for (Map.Entry<String, List<String>> e : model.items.entrySet()) {
                    int idx = parseIndex(e.getKey());
                    if (idx <= 0) continue;

                    List<String> snbtSlots = e.getValue();
                    if (snbtSlots == null) continue;

                    List<ItemStack> stacks = new ArrayList<>(snbtSlots.size());
                    for (String snbt : snbtSlots) {
                        stacks.add(deserializeItem(snbt));
                    }
                    p.itemsByIndex.put(idx, stacks);
                }
            }

            return p;
        } catch (Exception ex) {
            System.err.println("[MysticEssentials] Failed to load vault file for " + playerId + ": " + ex.getMessage());
            return new VaultProfile(playerId);
        }
    }

    @Override
    public void save(VaultProfile profile) {
        Path file = fileFor(profile.owner);

        JsonModel model = new JsonModel();
        model.owner = profile.owner.toString();

        model.meta = new TreeMap<>();
        for (Map.Entry<Integer, VaultMeta> e : profile.metaByIndex.entrySet()) {
            int idx = e.getKey() == null ? -1 : e.getKey();
            if (idx <= 0) continue;

            VaultMeta vm = e.getValue();
            if (vm == null) continue;

            JsonMeta jm = new JsonMeta();
            jm.name = blankToNull(vm.customBaseName);
            jm.item = blankToNull(vm.displayItemId);

            if (jm.name == null && jm.item == null) continue;
            model.meta.put(String.valueOf(idx), jm);
        }

        model.items = new TreeMap<>();
        for (Map.Entry<Integer, List<ItemStack>> e : profile.itemsByIndex.entrySet()) {
            int idx = e.getKey() == null ? -1 : e.getKey();
            if (idx <= 0) continue;

            List<ItemStack> list = e.getValue();
            if (list == null) continue;

            List<String> snbtSlots = new ArrayList<>(list.size());
            for (ItemStack stack : list) {
                snbtSlots.add(serializeItem(stack));
            }

            if (snbtSlots.stream().allMatch(Objects::isNull)) continue;
            model.items.put(String.valueOf(idx), snbtSlots);
        }

        try {
            Files.createDirectories(vaultDir);
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(model, w);
            }
        } catch (IOException ex) {
            System.err.println("[MysticEssentials] Failed to save vault file for " + profile.owner + ": " + ex.getMessage());
        }
    }

    @Override
    public void resetVault(UUID playerId, int vaultIndex, boolean clearItems, boolean resetMeta) {
        VaultProfile p = load(playerId);
        if (clearItems) p.itemsByIndex.remove(vaultIndex);
        if (resetMeta) p.metaByIndex.remove(vaultIndex);
        save(p);
    }

    @Override
    public void resetAll(UUID playerId) {
        Path file = fileFor(playerId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            System.err.println("[MysticEssentials] Failed to delete vault file for " + playerId + ": " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------

    private Path fileFor(UUID playerId) {
        return vaultDir.resolve(playerId.toString() + ".json");
    }

    private static int parseIndex(String s) {
        try { return Integer.parseInt(s); }
        catch (Exception ignored) { return -1; }
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** ItemStack -> SNBT string, null if empty */
    private String serializeItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        try {
            HolderLookup.Provider reg = this.registries;
            if (reg == null) return null; // not ready yet

            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, reg);
            Tag tag = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
            return tag == null ? null : tag.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** SNBT string -> ItemStack, EMPTY if invalid */
    private ItemStack deserializeItem(String snbt) {
        if (snbt == null || snbt.isBlank() || snbt.equals("{}")) return ItemStack.EMPTY;
        try {
            HolderLookup.Provider reg = this.registries;
            if (reg == null) return ItemStack.EMPTY;

            Tag tag = TagParser.parseTag(snbt);
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, reg);
            return ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }


    // ------------------------------------------------------------
    // JSON DTO
    // ------------------------------------------------------------

    private static final class JsonModel {
        String owner;
        Map<String, JsonMeta> meta;
        Map<String, List<String>> items;
    }

    private static final class JsonMeta {
        String name;
        String item;
    }
}
