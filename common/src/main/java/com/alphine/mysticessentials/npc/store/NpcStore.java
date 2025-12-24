package com.alphine.mysticessentials.npc.store;

import com.alphine.mysticessentials.npc.model.NpcDefinition;
import com.alphine.mysticessentials.storage.JsonIO;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NpcStore {
    private final Gson gson;
    private final Path dir;
    private final boolean atomic;

    private final Map<String, NpcDefinition> cache = new ConcurrentHashMap<>();

    public NpcStore(Gson gson, Path configDir, String directory, boolean atomic) {
        this.gson = gson;
        this.dir = configDir.resolve(directory);
        this.atomic = atomic;
    }

    public void loadAll() throws IOException {
        Files.createDirectories(dir);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                NpcDefinition def = JsonIO.read(gson, p, NpcDefinition.class);
                if (def != null && def.name != null && !def.name.isBlank()) {
                    cache.put(def.name.toLowerCase(Locale.ROOT), def);
                }
            }
        }
    }

    public void saveAll() throws IOException {
        for (NpcDefinition def : cache.values()) {
            if (def == null || def.name == null || def.name.isBlank()) continue;
            save(def.name);
        }
    }



    public void clear() { cache.clear(); }

    public void reloadAll() throws IOException {
        clear();
        loadAll();
    }

    public void rename(String oldName, String newName) throws IOException {
        var def = get(oldName);
        if (def == null) return;
        delete(oldName);
        def.name = newName;
        put(def);
        save(newName);
    }

    public Collection<NpcDefinition> list() {
        return Collections.unmodifiableCollection(cache.values());
    }

    public NpcDefinition get(String name) {
        return cache.get(name.toLowerCase(Locale.ROOT));
    }

    public void put(NpcDefinition def) {
        cache.put(def.name.toLowerCase(Locale.ROOT), def);
    }

    public void save(String name) throws IOException {
        NpcDefinition def = get(name);
        if (def == null) return;
        Path file = dir.resolve(def.name + ".json");
        JsonIO.write(gson, file, def, atomic);
    }

    public void delete(String name) throws IOException {
        NpcDefinition def = get(name);
        cache.remove(name.toLowerCase(Locale.ROOT));
        Path file = dir.resolve((def != null ? def.name : name) + ".json");
        JsonIO.deleteIfExists(file);
    }
}
