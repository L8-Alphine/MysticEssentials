package com.alphine.mysticessentials.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Per-player homes stored in /config/mysticessentials/homes.json
 * Layout: { "<uuid>": { "<homeName>": Home } }
 */
public class HomesStore {
    public static final class Home {
        public String name;
        public String dim;   // e.g. "minecraft:overworld"
        public double x, y, z;
        public float yaw, pitch;
    }

    private final Map<UUID, Map<String, Home>> data = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public HomesStore(Path cfgDir) {
        this.file = cfgDir.resolve("homes.json");
        load();
    }

    public synchronized void set(UUID id, Home h) {
        data.computeIfAbsent(id, k -> new HashMap<>()).put(h.name.toLowerCase(Locale.ROOT), h);
        save();
    }

    public synchronized Optional<Home> get(UUID id, String name) {
        var m = data.get(id);
        if (m == null) return Optional.empty();
        return Optional.ofNullable(m.get(name.toLowerCase(Locale.ROOT)));
    }

    public synchronized boolean delete(UUID id, String name) {
        var m = data.get(id);
        if (m == null) return false;
        boolean ok = m.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (ok) save();
        return ok;
    }

    public synchronized Collection<Home> all(UUID id) {
        var m = data.get(id);
        return m == null ? List.of() : List.copyOf(m.values());
    }

    public synchronized Set<String> names(UUID id) {
        var m = data.get(id);
        return m == null ? Set.of() : Set.copyOf(m.keySet());
    }

    public synchronized void load() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                save(); // create empty
                return;
            }
            try (Reader r = Files.newBufferedReader(file)) {
                var type = new com.google.gson.reflect.TypeToken<Map<String, Map<String, Home>>>() {}.getType();
                Map<String, Map<String, Home>> raw = gson.fromJson(r, type);
                data.clear();
                if (raw != null) {
                    for (var e : raw.entrySet()) {
                        data.put(UUID.fromString(e.getKey()), new HashMap<>(e.getValue()));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public synchronized void save() {
        try (Writer w = Files.newBufferedWriter(file)) {
            Map<String, Map<String, Home>> out = new LinkedHashMap<>();
            for (var e : data.entrySet()) out.put(e.getKey().toString(), e.getValue());
            gson.toJson(out, w);
        } catch (Exception ignored) {}
    }
}
