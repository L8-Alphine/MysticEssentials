package com.alphine.mysticessentials.hologram.store;

import com.alphine.mysticessentials.hologram.model.HologramDefinition;
import com.alphine.mysticessentials.storage.JsonIO;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HologramStore {
    private final Gson gson;
    private final Path dir;
    private final boolean atomic;

    private final Map<String, HologramDefinition> cache = new ConcurrentHashMap<>();

    public HologramStore(Gson gson, Path configDir, String directory, boolean atomic) {
        this.gson = gson;
        this.dir = configDir.resolve(directory);
        this.atomic = atomic;
    }

    /** Canonical key for cache + filename base. */
    public static String normalizeKey(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) return null;
        // safe file key
        return n.replaceAll("[^a-z0-9_\\-\\.]", "_");
    }

    private Path fileForKey(String key) {
        return dir.resolve(key + ".json");
    }

    private static String fileBaseName(Path p) {
        String fn = p.getFileName().toString();
        int dot = fn.lastIndexOf('.');
        return (dot <= 0) ? fn : fn.substring(0, dot);
    }

    public void loadAll() throws IOException {
        Files.createDirectories(dir);
        cache.clear();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                HologramDefinition def = JsonIO.read(gson, p, HologramDefinition.class);
                if (def == null) continue;

                String key = normalizeKey(def.name);
                if (key == null) continue;

                // enforce canonical name in memory
                def.name = key;

                // migrate filename to canonical (prevents duplicates like Test.json vs test.json)
                String base = normalizeKey(fileBaseName(p));
                Path canonical = fileForKey(key);
                if (base == null || !canonical.equals(p)) {
                    try {
                        if (!Files.exists(canonical)) {
                            Files.move(p, canonical, StandardCopyOption.ATOMIC_MOVE);
                        } else if (!canonical.equals(p)) {
                            // canonical exists already: delete old
                            Files.deleteIfExists(p);
                        }
                    } catch (IOException ignored) {
                        // if move fails (cross-device), ignore; we still load it
                    }
                }

                cache.put(key, def);
            }
        }
    }

    public void reloadAll() throws IOException {
        loadAll();
    }

    public void clear() {
        cache.clear();
    }

    public Collection<HologramDefinition> list() {
        return Collections.unmodifiableCollection(cache.values());
    }

    public HologramDefinition get(String name) {
        String key = normalizeKey(name);
        return key == null ? null : cache.get(key);
    }

    public void put(HologramDefinition def) {
        if (def == null) return;
        String key = normalizeKey(def.name);
        if (key == null) return;
        def.name = key;
        cache.put(key, def);
    }

    public void save(String name) throws IOException {
        String key = normalizeKey(name);
        if (key == null) return;

        HologramDefinition def = cache.get(key);
        if (def == null) return;

        def.name = key;
        JsonIO.write(gson, fileForKey(key), def, atomic);
    }

    public void delete(String name) throws IOException {
        String key = normalizeKey(name);
        if (key == null) return;

        cache.remove(key);
        Files.deleteIfExists(fileForKey(key));
    }

    public void rename(String oldName, String newName) throws IOException {
        String oldKey = normalizeKey(oldName);
        String newKey = normalizeKey(newName);
        if (oldKey == null || newKey == null) return;

        HologramDefinition def = cache.remove(oldKey);
        if (def == null) return;

        Files.deleteIfExists(fileForKey(oldKey));

        def.name = newKey;
        cache.put(newKey, def);
        save(newKey);
    }

    public void saveAll() throws IOException {
        for (HologramDefinition def : list()) {
            if (def == null || def.name == null || def.name.isBlank()) continue;
            save(def.name);
        }
    }
}
