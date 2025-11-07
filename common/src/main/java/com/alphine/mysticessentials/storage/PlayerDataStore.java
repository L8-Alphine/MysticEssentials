package com.alphine.mysticessentials.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class PlayerDataStore {
    public static final class LastLoc { public String dim; public double x,y,z; public float yaw,pitch; public long when; }
    public static final class Flags { public boolean tpToggle=false; public boolean tpAuto=false; }
    public static final class BackData {
        public Deque<LastLoc> stack = new ArrayDeque<>(); // LIFO; newest first
        public LastLoc lastDeath;                          // most recent death
    }

    private final Map<UUID, LastLoc> last = new HashMap<>();
    private final Map<UUID, Flags> flags = new HashMap<>();
    private final Map<UUID, BackData> back = new HashMap<>();
    private final Map<UUID, AfkSession> afk = new HashMap<>();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public PlayerDataStore(Path cfgDir){ this.file = cfgDir.resolve("playerdata.json"); load(); }

    // ---------- Last known location (e.g., for /tpo) ----------
    public synchronized void setLast(UUID id, LastLoc l){ last.put(id,l); save(); }
    public synchronized Optional<LastLoc> getLast(UUID id){ return Optional.ofNullable(last.get(id)); }

    // ---------- Flags ----------
    public synchronized Flags getFlags(UUID id){ return flags.computeIfAbsent(id,k->new Flags()); }
    public synchronized void saveFlags(UUID id, Flags f){ flags.put(id,f); save(); }

    // ---------- Back stack ----------
    public synchronized void pushBack(UUID id, LastLoc l) {
        BackData bd = back.computeIfAbsent(id, k -> new BackData());
        bd.stack.push(l);
        // cap the stack (avoid huge growth)
        while (bd.stack.size() > 20) bd.stack.removeLast();
        save();
    }
    public synchronized Optional<LastLoc> popBack(UUID id) {
        BackData bd = back.get(id);
        if (bd == null || bd.stack.isEmpty()) return Optional.empty();
        LastLoc loc = bd.stack.pop();
        save();
        return Optional.of(loc);
    }
    public synchronized Optional<LastLoc> peekBack(UUID id) {
        BackData bd = back.get(id);
        if (bd == null || bd.stack.isEmpty()) return Optional.empty();
        return Optional.of(bd.stack.peek());
    }

    // ---------- Death location ----------
    public synchronized void setDeath(UUID id, LastLoc l){ back.computeIfAbsent(id,k->new BackData()).lastDeath = l; save(); }
    public synchronized Optional<LastLoc> getDeath(UUID id){
        BackData bd = back.get(id);
        return bd == null ? Optional.empty() : Optional.ofNullable(bd.lastDeath);
    }

    // AFK Data
    public static final class AfkSession {
        public String message;          // per-session custom AFK message
        public LastLoc returnLoc;       // saved teleport-back location
    }

    // ---------- AFK: message ----------
    public synchronized void setAfkMessage(UUID id, String msg) {
        AfkSession s = afk.computeIfAbsent(id, k -> new AfkSession());
        s.message = msg;
        save();
    }
    public synchronized Optional<String> getAfkMessage(UUID id) {
        AfkSession s = afk.get(id);
        return s == null || s.message == null || s.message.isBlank() ? Optional.empty() : Optional.of(s.message);
    }
    public synchronized void clearAfkMessage(UUID id) {
        AfkSession s = afk.get(id);
        if (s != null) { s.message = null; save(); }
    }

    public synchronized void setAfkReturnLoc(UUID id, LastLoc loc) {
        AfkSession s = afk.computeIfAbsent(id, k -> new AfkSession());
        s.returnLoc = loc; save();
    }
    public synchronized Optional<LastLoc> getAfkReturnLoc(UUID id) {
        AfkSession s = afk.get(id);
        return s == null ? Optional.empty() : Optional.ofNullable(s.returnLoc);
    }
    public synchronized void clearAfkReturnLoc(UUID id) {
        AfkSession s = afk.get(id);
        if (s != null) { s.returnLoc = null; save(); }
    }


    // ---------- IO ----------
    @SuppressWarnings("unchecked")
    public synchronized void load(){
        try {
            Files.createDirectories(file.getParent());
            if(!Files.exists(file)){ save(); return; }
            try(Reader r = Files.newBufferedReader(file)){
                var type = new com.google.gson.reflect.TypeToken<Map<String,Object>>(){}.getType();
                Map<String,Object> root = gson.fromJson(r,type);
                last.clear(); flags.clear(); back.clear(); afk.clear();
                if(root!=null){
                    Map<String,Map<String,Object>> L = (Map<String,Map<String,Object>>)root.get("last");
                    Map<String,Map<String,Object>> F = (Map<String,Map<String,Object>>)root.get("flags");
                    Map<String,Map<String,Object>> B = (Map<String,Map<String,Object>>)root.get("back");
                    Map<String,Map<String,Object>> A = (Map<String,Map<String,Object>>)root.get("afk");

                    if(L!=null) for(var e:L.entrySet()){
                        LastLoc v = gson.fromJson(gson.toJsonTree(e.getValue()), LastLoc.class);
                        last.put(UUID.fromString(e.getKey()), v);
                    }
                    if(F!=null) for(var e:F.entrySet()){
                        Flags v = gson.fromJson(gson.toJsonTree(e.getValue()), Flags.class);
                        flags.put(UUID.fromString(e.getKey()), v);
                    }
                    if(B!=null) for(var e:B.entrySet()){
                        BackData bd = new BackData();
                        Map<String,Object> in = B.get(e.getKey());
                        List<Object> Ls = (List<Object>) in.get("stack");
                        if (Ls != null) for (Object o : Ls) {
                            bd.stack.addLast(gson.fromJson(gson.toJsonTree(o), LastLoc.class));
                        }
                        if (in.get("lastDeath") != null)
                            bd.lastDeath = gson.fromJson(gson.toJsonTree(in.get("lastDeath")), LastLoc.class);
                        back.put(UUID.fromString(e.getKey()), bd);
                    }
                    if (A != null) for (var e : A.entrySet()) {
                        AfkSession s = new AfkSession();
                        Map<String, Object> m = e.getValue();
                        if (m.get("message") != null) s.message = (String) m.get("message");
                        if (m.get("returnLoc") != null)
                            s.returnLoc = gson.fromJson(gson.toJsonTree(m.get("returnLoc")), LastLoc.class);
                        afk.put(UUID.fromString(e.getKey()), s);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public synchronized void save(){
        try(Writer w = Files.newBufferedWriter(file)){
            Map<String,Object> root = new LinkedHashMap<>();

            Map<String,Object> L = new LinkedHashMap<>(); last.forEach((k,v)->L.put(k.toString(), v));
            Map<String,Object> F = new LinkedHashMap<>(); flags.forEach((k,v)->F.put(k.toString(), v));

            Map<String,Object> B = new LinkedHashMap<>();
            back.forEach((k,bd)->{
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("stack", new ArrayList<>(bd.stack));
                m.put("lastDeath", bd.lastDeath);
                B.put(k.toString(), m);
            });

            Map<String, Object> A = new LinkedHashMap<>();
            afk.forEach((k, v) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("message", v.message);
                m.put("returnLoc", v.returnLoc);
                A.put(k.toString(), m);
            });

            root.put("last", L);
            root.put("flags", F);
            root.put("back", B);
            root.put("afk", A);

            gson.toJson(root, w);
        } catch (Exception ignored) {}
    }
}
