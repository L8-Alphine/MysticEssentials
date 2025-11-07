package com.alphine.mysticessentials.storage;

import com.alphine.mysticessentials.config.MEConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * JSON store for AFK Pools.
 *
 * File: <config/mysticessentials>/afk_pools.json
 *
 * Schema mirrors MEConfig.AfkPool, so AfkService can directly consume the map.
 */
public class AfkPoolsStore {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    // name -> pool
    private final Map<String, MEConfig.AfkPool> pools = new LinkedHashMap<>();

    public AfkPoolsStore(Path cfgDir) {
        this.file = cfgDir.resolve("afk_pools.json");
        load();
    }

    // ---------------- IO ----------------

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) { save(); return; }

            try (Reader r = Files.newBufferedReader(file)) {
                var type = new com.google.gson.reflect.TypeToken<Map<String, Object>>(){}.getType();
                Map<String, Object> root = gson.fromJson(r, type);
                pools.clear();
                if (root != null) {
                    for (var e : root.entrySet()) {
                        MEConfig.AfkPool p = gson.fromJson(gson.toJsonTree(e.getValue()), MEConfig.AfkPool.class);
                        sanitizePool(p);
                        pools.put(e.getKey(), p);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public synchronized void save() {
        try (Writer w = Files.newBufferedWriter(file)) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : pools.entrySet()) out.put(e.getKey(), e.getValue());
            gson.toJson(out, w);
        } catch (Exception ignored) {}
    }

    // ---------------- Accessors ----------------

    public synchronized Map<String, MEConfig.AfkPool> viewAll() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pools));
    }

    public synchronized Optional<MEConfig.AfkPool> get(String name) {
        return Optional.ofNullable(pools.get(name));
    }

    public synchronized void put(String name, MEConfig.AfkPool pool) {
        sanitizePool(pool);
        pools.put(name, pool);
        save();
    }

    public synchronized boolean remove(String name) {
        boolean b = (pools.remove(name) != null);
        if (b) save();
        return b;
    }

    // ---------------- Mutators / helpers ----------------

    public synchronized MEConfig.AfkPool getOrCreate(String name) {
        return pools.computeIfAbsent(name, n -> {
            MEConfig.AfkPool p = new MEConfig.AfkPool();
            p.enabled = true;
            if (p.region == null) p.region = new MEConfig.PoolBox();
            if (p.region.min == null) p.region.min = new MEConfig.Vec3i(0,0,0);
            if (p.region.max == null) p.region.max = new MEConfig.Vec3i(0,0,0);
            if (p.teleport == null) p.teleport = new MEConfig.TeleportPoint("minecraft:overworld", 0.5, 64, 0.5, 0f, 0f);
            return p;
        });
    }

    public synchronized void setEnabled(String name, boolean enabled) {
        getOrCreate(name).enabled = enabled;
        save();
    }

    public synchronized void setRequirePermission(String name, boolean require) {
        getOrCreate(name).requirePermission = require;
        save();
    }

    public synchronized void setAllowChat(String name, boolean allow) {
        getOrCreate(name).allowChatInside = allow;
        save();
    }

    public synchronized void setAllowExit(String name, boolean allow) {
        getOrCreate(name).allowEnterExitFreely = allow;
        save();
    }

    /** Sets the region from two corners; also normalizes min/max. */
    public synchronized void setRegion(String name, String world,
                                       int x1, int y1, int z1,
                                       int x2, int y2, int z2) {
        MEConfig.AfkPool p = getOrCreate(name);
        if (p.region == null) p.region = new MEConfig.PoolBox();
        p.region.world = world;
        int minX = Math.min(x1,x2), minY = Math.min(y1,y2), minZ = Math.min(z1,z2);
        int maxX = Math.max(x1,x2), maxY = Math.max(y1,y2), maxZ = Math.max(z1,z2);
        p.region.min = new MEConfig.Vec3i(minX, minY, minZ);
        p.region.max = new MEConfig.Vec3i(maxX, maxY, maxZ);
        save();
    }

    public synchronized void setTeleport(String name, MEConfig.TeleportPoint tp) {
        MEConfig.AfkPool p = getOrCreate(name);
        p.teleport = tp;
        save();
    }

    public synchronized void setTeleport(String name, String world, double x, double y, double z, float yaw, float pitch) {
        setTeleport(name, new MEConfig.TeleportPoint(world, x, y, z, yaw, pitch));
    }

    // ----- Rewards -----

    public synchronized MEConfig.AfkReward addOrUpdateRewardTrack(String name, String trackId, int everySeconds) {
        MEConfig.AfkPool p = getOrCreate(name);
        if (p.rewards == null) p.rewards = new ArrayList<>();
        for (MEConfig.AfkReward r : p.rewards) {
            if (r.id != null && r.id.equalsIgnoreCase(trackId)) {
                r.everySeconds = everySeconds;
                save();
                return r;
            }
        }
        MEConfig.AfkReward r = new MEConfig.AfkReward();
        r.id = trackId;
        r.everySeconds = everySeconds;
        if (r.commands == null) r.commands = new ArrayList<>();
        if (r.items == null) r.items = new ArrayList<>();
        p.rewards.add(r);
        save();
        return r;
    }

    public synchronized void addRewardCommand(String name, String trackId, String command) {
        MEConfig.AfkReward r = addOrUpdateRewardTrack(name, trackId, Math.max(1, getTrackEverySeconds(name, trackId)));
        if (r.commands == null) r.commands = new ArrayList<>();
        r.commands.add(command);
        save();
    }

    public synchronized void addRewardItem(String name, String trackId, String material, int amount, String nbtJson) {
        MEConfig.AfkReward r = addOrUpdateRewardTrack(name, trackId, Math.max(1, getTrackEverySeconds(name, trackId)));
        if (r.items == null) r.items = new ArrayList<>();
        MEConfig.ItemSpec it = new MEConfig.ItemSpec();
        it.type = material;
        it.amount = Math.max(1, amount);
        it.nbt = (nbtJson == null || nbtJson.isBlank()) ? "{}" : nbtJson;
        r.items.add(it);
        save();
    }

    public synchronized boolean removeRewardTrack(String name, String trackId) {
        MEConfig.AfkPool p = pools.get(name);
        if (p == null || p.rewards == null) return false;
        boolean removed = p.rewards.removeIf(r -> trackId.equalsIgnoreCase(r.id));
        if (removed) save();
        return removed;
    }

    // ---------------- Utils ----------------

    private int getTrackEverySeconds(String name, String trackId) {
        MEConfig.AfkPool p = pools.get(name);
        if (p == null || p.rewards == null) return 60;
        for (MEConfig.AfkReward r : p.rewards)
            if (r.id != null && r.id.equalsIgnoreCase(trackId))
                return Math.max(1, r.everySeconds);
        return 60;
    }

    private void sanitizePool(MEConfig.AfkPool p) {
        if (p == null) return;
        if (p.region == null) p.region = new MEConfig.PoolBox();
        if (p.region.min == null) p.region.min = new MEConfig.Vec3i(0,0,0);
        if (p.region.max == null) p.region.max = new MEConfig.Vec3i(0,0,0);
        if (p.teleport == null) p.teleport = new MEConfig.TeleportPoint("minecraft:overworld", 0.5, 64, 0.5, 0f, 0f);
        if (p.rewards == null) p.rewards = new ArrayList<>();
        // normalize min/max if accidentally inverted
        int minX = Math.min(p.region.min.x, p.region.max.x);
        int minY = Math.min(p.region.min.y, p.region.max.y);
        int minZ = Math.min(p.region.min.z, p.region.max.z);
        int maxX = Math.max(p.region.min.x, p.region.max.x);
        int maxY = Math.max(p.region.min.y, p.region.max.y);
        int maxZ = Math.max(p.region.min.z, p.region.max.z);
        p.region.min = new MEConfig.Vec3i(minX, minY, minZ);
        p.region.max = new MEConfig.Vec3i(maxX, maxY, maxZ);
    }
}
