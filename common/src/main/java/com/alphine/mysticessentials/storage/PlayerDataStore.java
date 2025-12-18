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
 * <p>
 * Contains:
 *  - identity: uuid, lastIp, username, nickname
 *  - playtime (total + sessionStart to accumulate)
 *  - flags
 *  - back (single last location, not a stack)
 *  - lastDeath
 *  - AFK session
 *  - homes (name -> Home)
 *  - kits  (lastClaim, usedOnce)
 *  - inventory snapshot (format + payload)
 *  - (optional snapshot) punishments relevant to player (warnings/mute/ban/frozen/jail)
 * <p>
 * Also migrates from:
 *  - config/mysticessentials/playerdata.json            (old monolith)
 *  - config/mysticessentials/homes.json                 (old HomesStore)
 *  - config/mysticessentials/kits_players.json          (old KitPlayerStore)
 *
 * Backwards-compat:
 *  - If a prior PlayerRecord had a "back.stack" (schema < 2), we keep the newest element as back.last
 *    and drop the rest.
 */
public class PlayerDataStore {

    // ---------- Data Types ----------
    public static final class LastLoc {
        public String dim;
        public double x,y,z;
        public float yaw,pitch;
        public long when;
    }
    public static final class Flags {
        public boolean tpToggle=false;
        public boolean tpAuto=false;
    }

    /** Since schema 2: single back location only. */
    public static final class BackDataV2 {
        public LastLoc last;            // single entry used by /back
        public LastLoc lastDeath;       // most recent death
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

    /** Generic, platform-agnostic inventory snapshot. */
    public static final class InventoryData {
        /** Arbitrary hint like "nbt", "fabric-inv", "neoforge-inv", "json-slots", etc. */
        public String format = "none";
        /** Free-form payload; caller decides schema. */
        public Map<String, Object> payload = new LinkedHashMap<>();
        /** Optional human note/version. */
        public String note = "";
        /** When captured. */
        public long at = 0L;
    }

    /** Optional per-player snapshot of punishments (for quick UI access). */
    public static final class PunishmentsView {
        public List<PunishStore.Warning> warnings = new ArrayList<>();
        public PunishStore.Ban ban;                     // UUID ban snapshot (if any)
        public PunishStore.Mute mute;                   // active mute (if any)
        public boolean frozen = false;                  // current freeze state
        public String jailed;                           // jail name if jailed
        public long snapAt = 0L;                        // when snapshot taken
    }

    public static final class Identity {
        public UUID uuid;               // redundant with key; handy in-file
        public String lastIp = "";
        public String username = "";
        public String nickname = "";
        /** history (distinct last 8) for auditing convenience */
        public Deque<String> ipHistory = new ArrayDeque<>();  // newest first
        public Deque<String> nameHistory = new ArrayDeque<>();
    }

    public static final class Playtime {
        /** Total accumulated online time in millis. */
        public long totalMillis = 0L;
        /** Null when offline; set to System.currentTimeMillis() at join. */
        public Long sessionStart = null;
        /** Helper: last seen timestamp (login/logout). */
        public long lastSeen = 0L;
    }

    /** Schema 2 (or later) record. */
    public static final class PlayerRecord {
        public int schema = 2;

        // identity/session
        public Identity id = new Identity();
        public Playtime play = new Playtime();

        // location/flags
        public LastLoc last;            // last logout/teleport anchor if you want
        public Flags flags = new Flags();
        public BackDataV2 back = new BackDataV2();
        public AfkSession afk = new AfkSession();

        // merged stores:
        public Map<String, Home> homes = new HashMap<>(); // lowercased key
        public KitsData kits = new KitsData();

        // inventory snapshot
        public InventoryData inventory = new InventoryData();

        // punishments quick view (optional snapshot)
        public PunishmentsView punish;
    }

    // ---------- Store ----------
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path baseDir; // .../config/mysticessentials/playerdata
    private final Path cfgDir;  // .../config/mysticessentials
    private final Map<UUID, PlayerRecord> cache = new HashMap<>();

    public PlayerDataStore(Path cfgDir) {
        this.cfgDir = cfgDir;
        this.baseDir = cfgDir.resolve("playerdata");
        try {
            Files.createDirectories(baseDir);
        } catch (Exception ignored) {}
        migrateOldStoresIfPresent();
    }

    // ---------- Identity ----------
    public synchronized void setIdentityBasic(UUID id, String username, String nickname) {
        PlayerRecord r = rec(id);
        if (r.id.uuid == null) r.id.uuid = id;
        if (username != null && !username.isBlank()) {
            pushDistinctFront(r.id.nameHistory, username, 8);
            r.id.username = username;
        }
        if (nickname != null) r.id.nickname = nickname;
        r.play.lastSeen = System.currentTimeMillis();
        save(id, r);
    }
    public synchronized void setLastIp(UUID id, String ip) {
        PlayerRecord r = rec(id);
        if (r.id.uuid == null) r.id.uuid = id;
        if (ip != null && !ip.isBlank()) {
            pushDistinctFront(r.id.ipHistory, ip, 8);
            r.id.lastIp = ip;
            save(id, r);
        }
    }
    public synchronized Optional<Identity> getIdentity(UUID id){
        return Optional.of(rec(id).id);
    }

    // ---------- Playtime ----------
    public synchronized void markLogin(UUID id) {
        PlayerRecord r = rec(id);
        long now = System.currentTimeMillis();
        if (r.play.sessionStart == null) r.play.sessionStart = now;
        r.play.lastSeen = now;
        save(id, r);
    }

    public synchronized void markLogout(UUID id) {
        PlayerRecord r = rec(id);
        long now = System.currentTimeMillis();
        if (r.play.sessionStart != null) {
            r.play.totalMillis += Math.max(0, now - r.play.sessionStart);
            r.play.sessionStart = null;
        }
        r.play.lastSeen = now;
        save(id, r);
    }

    public synchronized long getTotalPlaytimeMillis(UUID id) {
        PlayerRecord r = rec(id);
        long total = r.play.totalMillis;
        if (r.play.sessionStart != null) total += Math.max(0, System.currentTimeMillis() - r.play.sessionStart);
        return total;
    }

    /** Admin helper: set raw accumulated playtime (in millis). Does not touch sessionStart. */
    public synchronized void setTotalPlaytimeMillis(UUID id, long millis) {
        PlayerRecord r = rec(id);
        r.play.totalMillis = Math.max(0L, millis);
        save(id, r);
    }


    // ---------- Last ----------
    public synchronized void setLast(UUID id, LastLoc l) { PlayerRecord r = rec(id); r.last = l; save(id,r); }
    public synchronized Optional<LastLoc> getLast(UUID id) { return Optional.ofNullable(rec(id).last); }

    // ---------- Flags ----------
    public synchronized Flags getFlags(UUID id) {
        return rec(id).flags;
    }
    public synchronized void saveFlags(UUID id, Flags f) {
        PlayerRecord r = rec(id);
        r.flags = f;
        save(id,r);
    }
    public synchronized void setTpToggle(UUID id, boolean v){
        PlayerRecord r=rec(id);
        r.flags.tpToggle=v;
        save(id,r);
    }
    public synchronized void setTpAuto(UUID id, boolean v){
        PlayerRecord r=rec(id);
        r.flags.tpAuto=v;
        save(id,r);
    }

    // ---------- Back (single) ----------
    public synchronized void setBack(UUID id, LastLoc l){
        PlayerRecord r = rec(id);
        r.back.last = l;
        save(id, r);
    }
    /** Returns and clears the back location (consume semantics). */
    public synchronized Optional<LastLoc> consumeBack(UUID id){
        PlayerRecord r = rec(id);
        if (r.back.last == null) return Optional.empty();
        LastLoc out = r.back.last;
        r.back.last = null;
        save(id, r);
        return Optional.of(out);
    }
    public synchronized Optional<LastLoc> peekBack(UUID id){
        return Optional.ofNullable(rec(id).back.last);
    }
    public synchronized void setDeath(UUID id, LastLoc l){
        PlayerRecord r=rec(id);
        r.back.lastDeath=l;
        save(id,r);
    }
    public synchronized Optional<LastLoc> getDeath(UUID id){
        return Optional.ofNullable(rec(id).back.lastDeath);
    }

    // ---------- AFK ----------
    public synchronized void setAfkMessage(UUID id, String msg){
        PlayerRecord r=rec(id);
        r.afk.message = msg==null?"":msg;
        save(id,r);
    }
    public synchronized Optional<String> getAfkMessage(UUID id){
        String m = rec(id).afk.message;
        return (m==null || m.isBlank())? Optional.empty() : Optional.of(m);
    }
    public synchronized void clearAfkMessage(UUID id){
        PlayerRecord r=rec(id); r.afk.message="";
        save(id,r);
    }
    public synchronized void setAfkReturnLoc(UUID id, LastLoc loc){
        PlayerRecord r=rec(id);
        r.afk.returnLoc=loc;
        save(id,r);
    }
    public synchronized Optional<LastLoc> getAfkReturnLoc(UUID id){
        return Optional.ofNullable(rec(id).afk.returnLoc);
    }
    public synchronized void clearAfkReturnLoc(UUID id){
        PlayerRecord r=rec(id);
        r.afk.returnLoc=null;
        save(id,r);
    }

    // ---------- Homes ----------
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

    // ---------- Kits ----------
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

    // ---------- Inventory ----------
    public synchronized void saveInventory(UUID id, String format, Map<String,Object> payload, String note){
        PlayerRecord r = rec(id);
        if (r.inventory == null) r.inventory = new InventoryData();
        r.inventory.format = format == null? "none" : format;
        r.inventory.payload = payload == null? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        r.inventory.note = note == null? "" : note;
        r.inventory.at = System.currentTimeMillis();
        save(id, r);
    }
    public synchronized InventoryData getInventory(UUID id){
        PlayerRecord r = rec(id);
        if (r.inventory == null) r.inventory = new InventoryData();
        return r.inventory;
    }

    // ---------- Punishments Snapshot (optional helper) ----------
    /** Take a point-in-time snapshot from PunishStore into this player's record for easy UI access. */
    public synchronized void snapshotPunishments(UUID id, PunishStore punish) {
        PlayerRecord r = rec(id);
        if (r.punish == null) r.punish = new PunishmentsView();
        r.punish.snapAt = System.currentTimeMillis();
        // warnings
        r.punish.warnings = new ArrayList<>(punish.getWarnings(id));
        // uuid ban
        r.punish.ban = punish.getUuidBan(id).orElse(null);
        // mute
        r.punish.mute = punish.getMute(id).orElse(null);
        // frozen
        r.punish.frozen = punish.isFrozen(id);
        // jail
        r.punish.jailed = punish.getJailed(id).orElse(null);
        save(id, r);
    }
    public synchronized Optional<PunishmentsView> getPunishmentsView(UUID id){
        return Optional.ofNullable(rec(id).punish);
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
                    normalize(pr, id);
                    // If old schema (<2), upgrade back stack -> single
                    if (pr.schema < 2) {
                        upgradeSchemaTo2(pr);
                        pr.schema = 2;
                        save(id, pr);
                    }
                    return pr;
                }
            } else {
                PlayerRecord pr = new PlayerRecord();
                pr.id.uuid = id;
                save(id, pr);
                return pr;
            }
        } catch (Exception e) {
            e.printStackTrace();
            PlayerRecord pr = new PlayerRecord();
            pr.id.uuid = id;
            save(id, pr);
            return pr;
        }
    }

    private void normalize(PlayerRecord pr, UUID id){
        if (pr.schema <= 0) pr.schema = 2;
        if (pr.id == null) pr.id = new Identity();
        if (pr.id.uuid == null) pr.id.uuid = id;
        if (pr.id.ipHistory == null) pr.id.ipHistory = new ArrayDeque<>();
        if (pr.id.nameHistory == null) pr.id.nameHistory = new ArrayDeque<>();
        if (pr.play == null) pr.play = new Playtime();

        if (pr.flags == null) pr.flags = new Flags();
        if (pr.back == null) pr.back = new BackDataV2();
        if (pr.afk == null) pr.afk = new AfkSession();
        if (pr.homes == null) pr.homes = new HashMap<>();
        if (pr.kits == null) pr.kits = new KitsData();
        if (pr.kits.lastClaim == null) pr.kits.lastClaim = new HashMap<>();
        if (pr.kits.usedOnce == null) pr.kits.usedOnce = new HashSet<>();
        if (pr.inventory == null) pr.inventory = new InventoryData();
        if (pr.afk.message == null) pr.afk.message = "";
    }

    /** Upgrades legacy records that used BackData with a stack to the single-entry model. */
    @SuppressWarnings("unchecked")
    private void upgradeSchemaTo2(PlayerRecord pr){
        // If someone manually deserialized older class fields into 'back' with 'stack', try to reflect via map.
        try {
            // Nothing to do if we already have a single 'last'
            if (pr.back != null && pr.back.last != null) return;

            // The older JSON had: back: { stack: [LastLoc...], lastDeath: {...} }
            // Gson would drop unknowns, so we can’t access stack directly here unless the file
            // was loaded into a generic map first. To be safe, we’ll do nothing if it's already gone.
            // However, since we call migrateOldStoresIfPresent() below which reads old monoliths,
            // most legacy stacks are handled there. This method remains for safety.
        } catch (Exception ignored) {}
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
            save(e.getKey(), e.getValue());
        }
    }

    /** Back-compat alias for callers that expect pdata.save(). */
    public synchronized void save() { saveAll(); }

    // ---------- Migration of separate old stores ----------
    // This stays here to migrate from old monolithic stores.
    // Incase someone still uses those old files.
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
                                // Legacy stack -> single
                                List<Object> list = (List<Object>) in.get("stack");
                                if (list != null && !list.isEmpty()) {
                                    // newest first in old code, so peek head (index 0)
                                    LastLoc newest = gson.fromJson(gson.toJsonTree(list.get(0)), LastLoc.class);
                                    if (pr.back == null) pr.back = new BackDataV2();
                                    pr.back.last = newest;
                                }
                                if (in.get("lastDeath") != null) {
                                    if (pr.back == null) pr.back = new BackDataV2();
                                    pr.back.lastDeath = gson.fromJson(gson.toJsonTree(in.get("lastDeath")), LastLoc.class);
                                }
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
                        // ensure schema
                        pr.schema = Math.max(pr.schema, 2);
                        normalize(pr, id);
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
                        pr.schema = Math.max(pr.schema, 2);
                        normalize(pr, id);
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
                        pr.schema = Math.max(pr.schema, 2);
                        normalize(pr, id);
                        save(id, pr);
                        cache.put(id, pr);
                    }
                }
                Files.move(oldKits, oldKits.resolveSibling("kits_players.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    // ---------- helpers ----------
    private static void pushDistinctFront(Deque<String> dq, String value, int max){
        if (value == null) return;
        dq.removeIf(v -> v.equalsIgnoreCase(value));
        dq.addFirst(value);
        while (dq.size() > max) dq.removeLast();
    }
}
