package com.alphine.mysticessentials.storage;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class KitPlayerStore {
    public static final class PlayerData {
        public Map<String, Long> lastClaim = new HashMap<>(); // kits -> last millis
        public Set<String> usedOnce = new HashSet<>();        // kits claimed if oneTime
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // In-memory: player -> data
    private final Map<UUID, PlayerData> data = new HashMap<>();

    public KitPlayerStore(Path cfgDir){
        this.file = cfgDir.resolve("kits_players.json");
        load();
    }

    public synchronized PlayerData get(UUID uuid){
        return data.computeIfAbsent(uuid, k -> new PlayerData());
    }

    public synchronized long getLast(UUID uuid, String kit){
        return get(uuid).lastClaim.getOrDefault(kit.toLowerCase(Locale.ROOT), 0L);
    }

    public synchronized void setLast(UUID uuid, String kit, long when){
        get(uuid).lastClaim.put(kit.toLowerCase(Locale.ROOT), when);
        save();
    }

    public synchronized boolean hasUsedOnce(UUID uuid, String kit){
        return get(uuid).usedOnce.contains(kit.toLowerCase(Locale.ROOT));
    }

    public synchronized void markUsedOnce(UUID uuid, String kit){
        get(uuid).usedOnce.add(kit.toLowerCase(Locale.ROOT));
        save();
    }

    private void load(){
        try {
            Files.createDirectories(file.getParent());
            if(!Files.exists(file)){ save(); return; }
            try (Reader r = Files.newBufferedReader(file)) {
                class Root { Map<String, PlayerData> players = new HashMap<>(); }
                Root root = gson.fromJson(r, Root.class);
                data.clear();
                if (root != null && root.players != null) {
                    for (var e : root.players.entrySet()) {
                        data.put(UUID.fromString(e.getKey()), e.getValue());
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void save(){
        try (Writer w = Files.newBufferedWriter(file)) {
            Map<String,Object> root = new LinkedHashMap<>();
            Map<String,PlayerData> map = new LinkedHashMap<>();
            for (var e : data.entrySet()) map.put(e.getKey().toString(), e.getValue());
            root.put("players", map);
            gson.toJson(root, w);
        } catch (Exception ignored) {}
    }
}
