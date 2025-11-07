package com.alphine.mysticessentials.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.util.*;

/**
 * Unified per-player storage:
 *   config/mysticessentials/playerdata/<UUID>.json
 *
 * Contains:
 *  - last location
 *  - flags
 *  - back stack + lastDeath
 *  - AFK session
 *  - homes (name -> Home)
 *  - kits (lastClaim, usedOnce)
 *
 * Also migrates from:
 *  - config/mysticessentials/playerdata.json            (old monolith)
 *  - config/mysticessentials/homes.json                 (old HomesStore)
 *  - config/mysticessentials/kits_players.json          (old KitPlayerStore)
 */
public class PlayerDataStore {

    // ---------- Data Types ----------
    public static final class LastLoc { public String dim; public double x,y,z; public float yaw,pitch; public long when; }
    public static final class Flags { public boolean tpToggle=false; public boolean tpAuto=false; }
    public static final class BackData {
        public Deque<LastLoc> stack = new ArrayDeque<>();
        public LastLoc lastDeath;
    }
    public static final class AfkSession {
        public String message = "";
        public LastLoc returnLoc;
    }
    public static final class Home {
        public String name;
        public String dim;
        public double x,y,z;
        public float yaw,pitch;
    }
    public static final class KitsData {
        public Map<String, Long> lastClaim = new HashMap<>(); // kit -> last millis
        public Set<String> usedOnce = new HashSet<>();
    }
    public static final class PlayerRecord {
        public int schema = 1;
        public LastLoc last;
        public Flags flags = new Flags();
        public BackData back = new BackData();
        public AfkSession afk = new AfkSession();

        // merged stores:
        public Map<String, Home> homes = new HashMap<>(); // lowercased key
        public KitsData kits = new KitsData();
    }

    // ---------- Store ----------
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path baseDir; // .../config/mysticessentials/playerdata
    private final Path cfgDir;  // .../config/mysticessentials
    private final Map<UUID, PlayerRecord> cache = new HashMap<>();

    public PlayerDataStore(Path cfgDir) {
        this.cfgDir = cfgDir;
        this.baseDir = cfgDir.resolve("playerdata");
        try { Files.createDirectories(baseDir); } catch (Exception ignored) {}
        migrateOldStoresIfPresent();
    }

    // ---------- Public API: Last ----------
    public synchronized void setLast(UUID id, LastLoc l) { PlayerRecord r = rec(id); r.last = l; save(id,r); }
    public synchronized Optional<LastLoc> getLast(UUID id) { return Optional.ofNullable(rec(id).last); }

    // ---------- Public API: Flags ----------
    public synchronized Flags getFlags(UUID id) { return rec(id).flags; }
    public synchronized void saveFlags(UUID id, Flags f) { PlayerRecord r = rec(id); r.flags = f; save(id,r); }
    public synchronized void setTpToggle(UUID id, boolean v){ PlayerRecord r=rec(id); r.flags.tpToggle=v; save(id,r); }
    public synchronized void setTpAuto(UUID id, boolean v){ PlayerRecord r=rec(id); r.flags.tpAuto=v; save(id,r); }

    // ---------- Public API: Back ----------
    public synchronized void pushBack(UUID id, LastLoc l) {
        PlayerRecord r = rec(id);
        r.back.stack.push(l);
        while (r.back.stack.size() > 20) r.back.stack.removeLast();
        save(id,r);
    }
    public synchronized Optional<LastLoc> popBack(UUID id) {
        PlayerRecord r = rec(id);
        if (r.back.stack.isEmpty()) return Optional.empty();
        LastLoc l = r.back.stack.pop(); save(id,r); return Optional.of(l);
    }
    public synchronized Optional<LastLoc> peekBack(UUID id) {
        PlayerRecord r = rec(id);
        return r.back.stack.isEmpty()? Optional.empty() : Optional.of(r.back.stack.peek());
    }
    public synchronized void setDeath(UUID id, LastLoc l){ PlayerRecord r=rec(id); r.back.lastDeath=l; save(id,r); }
    public synchronized Optional<LastLoc> getDeath(UUID id){ return Optional.ofNullable(rec(id).back.lastDeath); }

    // ---------- Public API: AFK ----------
    public synchronized void setAfkMessage(UUID id, String msg){ PlayerRecord r=rec(id); r.afk.message = msg==null?"":msg; save(id,r); }
    public synchronized Optional<String> getAfkMessage(UUID id){
        String m = rec(id).afk.message; return (m==null || m.isBlank())? Optional.empty() : Optional.of(m);
    }
    public synchronized void clearAfkMessage(UUID id){ PlayerRecord r=rec(id); r.afk.message=""; save(id,r); }
    public synchronized void setAfkReturnLoc(UUID id, LastLoc loc){ PlayerRecord r=rec(id); r.afk.returnLoc=loc; save(id,r); }
    public synchronized Optional<LastLoc> getAfkReturnLoc(UUID id){ return Optional.ofNullable(rec(id).afk.returnLoc); }
    public synchronized void clearAfkReturnLoc(UUID id){ PlayerRecord r=rec(id); r.afk.returnLoc=null; save(id,r); }

    // ---------- Public API: Homes (merged) ----------
    public synchronized void setHome(UUID id, Home h) {
        PlayerRecord r = rec(id);
        String key = h.name.toLowerCase(Locale.ROOT);
        r.homes.put(key, h);
        save(id, r);
    }
    public synchronized Optional<Home> getHome(UUID id, String name) {
        PlayerRecord r = rec(id);
        Home h = r.homes.get(name.toLowerCase(Locale.ROOT));
        return Optional.ofNullable(h);
    }
    public synchronized boolean delHome(UUID id, String name) {
        PlayerRecord r = rec(id);
        boolean ok = r.homes.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (ok) save(id, r);
        return ok;
    }
    public synchronized Collection<Home> allHomes(UUID id) {
        return List.copyOf(rec(id).homes.values());
    }
    public synchronized Set<String> homeNames(UUID id) {
        return Set.copyOf(rec(id).homes.keySet());
    }

    // ---------- Public API: Kits (merged) ----------
    public synchronized long getKitLast(UUID id, String kit) {
        return rec(id).kits.lastClaim.getOrDefault(kit.toLowerCase(Locale.ROOT), 0L);
    }
    public synchronized void setKitLast(UUID id, String kit, long when) {
        PlayerRecord r = rec(id);
        r.kits.lastClaim.put(kit.toLowerCase(Locale.ROOT), when);
        save(id, r);
    }
    public synchronized boolean hasUsedKitOnce(UUID id, String kit) {
        return rec(id).kits.usedOnce.contains(kit.toLowerCase(Locale.ROOT));
    }
    public synchronized void markKitUsedOnce(UUID id, String kit) {
        PlayerRecord r = rec(id);
        r.kits.usedOnce.add(kit.toLowerCase(Locale.ROOT));
        save(id, r);
    }

    public synchronized Map<String, Long> getAllKitLast(UUID id) {
        return new HashMap<>(rec(id).kits.lastClaim);
    }

    public synchronized Set<String> getAllKitsUsedOnce(UUID id) {
        return new HashSet<>(rec(id).kits.usedOnce);
    }

    // ---------- Internal ----------
    private Path fileOf(UUID id){ return baseDir.resolve(id.toString()+".json"); }

    private synchronized PlayerRecord rec(UUID id){
        return cache.computeIfAbsent(id, this::loadOrCreate);
    }

    private PlayerRecord loadOrCreate(UUID id){
        Path f = fileOf(id);
        try {
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    PlayerRecord pr = gson.fromJson(r, PlayerRecord.class);
                    if (pr == null) pr = new PlayerRecord();
                    if (pr.schema <= 0) pr.schema = 1;
                    normalize(pr);
                    return pr;
                }
            } else {
                PlayerRecord pr = new PlayerRecord(); // defaults preloaded here
                // (optionally set default homes/kits here)
                save(id, pr);
                return pr;
            }
        } catch (Exception e) {
            e.printStackTrace();
            PlayerRecord pr = new PlayerRecord();
            save(id, pr);
            return pr;
        }
    }

    private void normalize(PlayerRecord pr){
        if (pr.flags == null) pr.flags = new Flags();
        if (pr.back == null) pr.back = new BackData();
        if (pr.afk == null) pr.afk = new AfkSession();
        if (pr.homes == null) pr.homes = new HashMap<>();
        if (pr.kits == null) pr.kits = new KitsData();
        if (pr.back.stack == null) pr.back.stack = new ArrayDeque<>();
        if (pr.afk.message == null) pr.afk.message = "";
        if (pr.kits.lastClaim == null) pr.kits.lastClaim = new HashMap<>();
        if (pr.kits.usedOnce == null) pr.kits.usedOnce = new HashSet<>();
    }

    private void save(UUID id, PlayerRecord r){
        Path f = fileOf(id);
        try {
            Files.createDirectories(baseDir);
            Path tmp = Files.createTempFile(baseDir, id.toString(), ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                gson.toJson(r, w);
            }
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException amnse) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Force-write all cached player records to disk (optional; we already save on mutate). */
    public synchronized void saveAll() {
        for (var e : cache.entrySet()) {
            save(e.getKey(), e.getValue()); // calls the existing private per-player writer
        }
    }

    /** Back-compat alias for callers that expect pdata.save(). */
    public synchronized void save() {
        saveAll();
    }

    // ---------- Migration ----------
    @SuppressWarnings("unchecked")
    private void migrateOldStoresIfPresent() {
        // old monolith
        Path oldMonolith = cfgDir.resolve("playerdata.json");
        if (Files.exists(oldMonolith)) {
            try (Reader r = Files.newBufferedReader(oldMonolith)) {
                var type = new com.google.gson.reflect.TypeToken<Map<String,Object>>(){}.getType();
                Map<String,Object> root = gson.fromJson(r, type);
                if (root != null) {
                    Map<String,Map<String,Object>> L = (Map<String,Map<String,Object>>)root.get("last");
                    Map<String,Map<String,Object>> F = (Map<String,Map<String,Object>>)root.get("flags");
                    Map<String,Map<String,Object>> B = (Map<String,Map<String,Object>>)root.get("back");
                    Map<String,Map<String,Object>> A = (Map<String,Map<String,Object>>)root.get("afk");
                    Set<String> uuids = new HashSet<>();
                    if (L != null) uuids.addAll(L.keySet());
                    if (F != null) uuids.addAll(F.keySet());
                    if (B != null) uuids.addAll(B.keySet());
                    if (A != null) uuids.addAll(A.keySet());
                    for (String s : uuids) {
                        UUID id; try { id = UUID.fromString(s); } catch (Exception ignore) { continue; }
                        PlayerRecord pr = loadOrCreate(id);
                        if (L != null && L.containsKey(s)) pr.last = gson.fromJson(gson.toJsonTree(L.get(s)), LastLoc.class);
                        if (F != null && F.containsKey(s)) pr.flags = gson.fromJson(gson.toJsonTree(F.get(s)), Flags.class);
                        if (B != null && B.containsKey(s)) {
                            Map<String,Object> in = B.get(s);
                            if (in != null) {
                                List<Object> list = (List<Object>) in.get("stack");
                                if (list != null) for (Object o : list)
                                    pr.back.stack.addLast(gson.fromJson(gson.toJsonTree(o), LastLoc.class));
                                if (in.get("lastDeath") != null)
                                    pr.back.lastDeath = gson.fromJson(gson.toJsonTree(in.get("lastDeath")), LastLoc.class);
                            }
                        }
                        if (A != null && A.containsKey(s)) {
                            Map<String,Object> m = A.get(s);
                            if (m != null) {
                                if (m.get("message") != null) pr.afk.message = String.valueOf(m.get("message"));
                                if (m.get("returnLoc") != null)
                                    pr.afk.returnLoc = gson.fromJson(gson.toJsonTree(m.get("returnLoc")), LastLoc.class);
                            }
                        }
                        save(id, pr);
                        cache.put(id, pr);
                    }
                }
                Files.move(oldMonolith, oldMonolith.resolveSibling("playerdata.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) { e.printStackTrace(); }
        }

        // old homes.json
        Path oldHomes = cfgDir.resolve("homes.json");
        if (Files.exists(oldHomes)) {
            try (Reader r = Files.newBufferedReader(oldHomes)) {
                var type = new com.google.gson.reflect.TypeToken<Map<String, Map<String, Home>>>(){}.getType();
                Map<String, Map<String, Home>> raw = gson.fromJson(r, type);
                if (raw != null) {
                    for (var e : raw.entrySet()) {
                        UUID id; try { id = UUID.fromString(e.getKey()); } catch (Exception ignore) { continue; }
                        PlayerRecord pr = loadOrCreate(id);
                        for (var he : e.getValue().entrySet()) {
                            String key = he.getKey().toLowerCase(Locale.ROOT);
                            Home h = he.getValue();
                            if (h != null) {
                                if (h.name == null || h.name.isBlank()) h.name = he.getKey();
                                pr.homes.put(key, h);
                            }
                        }
                        save(id, pr);
                        cache.put(id, pr);
                    }
                }
                Files.move(oldHomes, oldHomes.resolveSibling("homes.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) { e.printStackTrace(); }
        }

        // old kits_players.json
        Path oldKits = cfgDir.resolve("kits_players.json");
        if (Files.exists(oldKits)) {
            try (Reader r = Files.newBufferedReader(oldKits)) {
                class Root { Map<String, KitsData> players = new HashMap<>(); }
                Root root = gson.fromJson(r, Root.class);
                if (root != null && root.players != null) {
                    for (var e : root.players.entrySet()) {
                        UUID id; try { id = UUID.fromString(e.getKey()); } catch (Exception ignore) { continue; }
                        PlayerRecord pr = loadOrCreate(id);
                        KitsData kd = e.getValue();
                        if (kd != null) {
                            if (kd.lastClaim == null) kd.lastClaim = new HashMap<>();
                            if (kd.usedOnce == null) kd.usedOnce = new HashSet<>();
                            // normalize keys to lower
                            Map<String, Long> lc = new HashMap<>();
                            kd.lastClaim.forEach((k,v) -> lc.put(k.toLowerCase(Locale.ROOT), v));
                            Set<String> uo = new HashSet<>();
                            kd.usedOnce.forEach(k -> uo.add(k.toLowerCase(Locale.ROOT)));
                            pr.kits.lastClaim.putAll(lc);
                            pr.kits.usedOnce.addAll(uo);
                        }
                        save(id, pr);
                        cache.put(id, pr);
                    }
                }
                Files.move(oldKits, oldKits.resolveSibling("kits_players.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) { e.printStackTrace(); }
        }
    }
}
