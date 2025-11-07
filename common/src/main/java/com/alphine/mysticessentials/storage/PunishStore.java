package com.alphine.mysticessentials.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.util.*;

public class PunishStore {
    public static final class Warning {
        public String id; public UUID target; public UUID actor; public String reason; public long at;
    }
    public static final class Ban {
        public UUID target; public String ip; public UUID actor; public String reason;
        public long at; public Long until; // null = permanent
    }
    public static final class Mute {
        public UUID target; public UUID actor; public String reason; public long at; public Long until;
    }
    public static final class Freeze { public UUID target; public long at; }
    public static final class Point { public String dim; public double x,y,z; public float yaw,pitch; }
    public static final class Jails {
        public Map<String, Point> locations = new HashMap<>();  // name -> point
        public Map<UUID, String> jailed = new HashMap<>();      // player -> jail name
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID,List<Warning>> warnings = new HashMap<>();
    private final Map<UUID,Ban> uuidBans = new HashMap<>();
    private final Map<String,Ban> ipBans = new HashMap<>();
    private final Map<UUID,Mute> mutes = new HashMap<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final Jails jails = new Jails();

    public PunishStore(Path cfgDir){ this.file = cfgDir.resolve("moderation.json"); load(); }

    // ---- warnings ----
    public synchronized Warning addWarning(UUID target, UUID actor, String reason){
        Warning w = new Warning();
        w.id = UUID.randomUUID().toString().substring(0,8);
        w.target=target; w.actor=actor; w.reason=reason; w.at=System.currentTimeMillis();
        warnings.computeIfAbsent(target,k->new ArrayList<>()).add(w);
        save(); return w;
    }
    public synchronized List<Warning> getWarnings(UUID target){ return List.copyOf(warnings.getOrDefault(target, List.of())); }
    public synchronized boolean clearWarnings(UUID target){ boolean ok = warnings.remove(target)!=null; if(ok) save(); return ok; }
    public synchronized boolean pardonWarning(UUID target, String id){
        var list = warnings.get(target); if(list==null) return false;
        boolean ok = list.removeIf(w->w.id.equalsIgnoreCase(id));
        if(ok) save(); return ok;
    }

    // ---- bans ----
    public synchronized void banUuid(Ban b){ uuidBans.put(b.target,b); save(); }
    public synchronized void banIp(Ban b){ ipBans.put(b.ip,b); save(); }
    public synchronized void unbanUuid(UUID t){ if(uuidBans.remove(t)!=null) save(); }
    public synchronized void unbanIp(String ip){ if(ipBans.remove(ip)!=null) save(); }
    public synchronized Optional<Ban> getUuidBan(UUID t){ return Optional.ofNullable(uuidBans.get(t)); }
    public synchronized Optional<Ban> getIpBan(String ip){ return Optional.ofNullable(ipBans.get(ip)); }
    public synchronized Collection<Ban> allBans(){ ArrayList<Ban> out=new ArrayList<>(uuidBans.values()); out.addAll(ipBans.values()); return out; }

    // ---- mutes ----
    public synchronized void mute(Mute m){ mutes.put(m.target,m); save(); }
    public synchronized void unmute(UUID t){ if(mutes.remove(t)!=null) save(); }
    public synchronized Optional<Mute> getMute(UUID t){ return Optional.ofNullable(mutes.get(t)); }

    // ---- freeze ----
    public synchronized boolean toggleFreeze(UUID t){
        if(frozen.contains(t)){ frozen.remove(t); save(); return false; }
        frozen.add(t); save(); return true;
    }
    public synchronized boolean isFrozen(UUID t){ return frozen.contains(t); }

    // ---- jail ----
    public synchronized void setJail(String name, Point p){ jails.locations.put(name.toLowerCase(Locale.ROOT), p); save(); }
    public synchronized boolean delJail(String name){ boolean ok = jails.locations.remove(name.toLowerCase(Locale.ROOT))!=null; if(ok) save(); return ok; }
    public synchronized Set<String> jailNames(){ return Set.copyOf(jails.locations.keySet()); }
    public synchronized Optional<Point> getJail(String name){ return Optional.ofNullable(jails.locations.get(name.toLowerCase(Locale.ROOT))); }
    public synchronized void jail(UUID u, String name){ jails.jailed.put(u, name.toLowerCase(Locale.ROOT)); save(); }
    public synchronized void unjail(UUID u){ if(jails.jailed.remove(u)!=null) save(); }
    public synchronized Optional<String> getJailed(UUID u){ return Optional.ofNullable(jails.jailed.get(u)); }

    // ---- IO ----
    public synchronized void load(){
        try {
            Files.createDirectories(file.getParent());
            if(!Files.exists(file)){ save(); return; }
            try(Reader r = Files.newBufferedReader(file)){
                var root = gson.fromJson(r, Map.class);
                warnings.clear(); uuidBans.clear(); ipBans.clear(); mutes.clear(); frozen.clear();
            }
        } catch (Exception ignored) {}
        // re-parse strongly (avoid Map.class generic limits)
        try (Reader r = Files.newBufferedReader(file)) {
            class Root {
                Map<String,List<Warning>> warnings = new HashMap<>();
                Map<String,Ban> uuidBans = new HashMap<>();
                Map<String,Ban> ipBans = new HashMap<>();
                Map<String,Mute> mutes = new HashMap<>();
                Set<String> frozen = new HashSet<>();
                Jails jails = new Jails();
            }
            Root root = gson.fromJson(r, Root.class);
            if(root!=null){
                root.warnings.forEach((k,v)->warnings.put(UUID.fromString(k), v==null?List.of():v));
                root.uuidBans.forEach((k,v)->uuidBans.put(UUID.fromString(k), v));
                if(root.ipBans!=null) ipBans.putAll(root.ipBans);
                root.mutes.forEach((k,v)->mutes.put(UUID.fromString(k), v));
                if(root.frozen!=null) root.frozen.forEach(s->frozen.add(UUID.fromString(s)));
                if(root.jails!=null){
                    jails.locations = root.jails.locations==null?new HashMap<>():root.jails.locations;
                    jails.jailed = root.jails.jailed==null?new HashMap<>():root.jails.jailed;
                }
            }
        } catch (Exception ignored) {}
    }
    public synchronized void save(){
        try(Writer w = Files.newBufferedWriter(file)){
            Map<String,Object> root = new LinkedHashMap<>();
            Map<String,List<Warning>> W=new LinkedHashMap<>();
            warnings.forEach((k,v)->W.put(k.toString(), v));
            Map<String,Ban> UB=new LinkedHashMap<>(); uuidBans.forEach((k,v)->UB.put(k.toString(), v));
            Map<String,Mute> M=new LinkedHashMap<>(); mutes.forEach((k,v)->M.put(k.toString(), v));
            Set<String> F=new LinkedHashSet<>(); frozen.forEach(u->F.add(u.toString()));
            Map<String,Object> J=new LinkedHashMap<>();
            J.put("locations", jails.locations);
            J.put("jailed", jails.jailed);
            root.put("warnings", W); root.put("uuidBans", UB); root.put("ipBans", ipBans);
            root.put("mutes", M); root.put("frozen", F); root.put("jails", J);
            gson.toJson(root, w);
        } catch (Exception ignored) {}
    }
}
