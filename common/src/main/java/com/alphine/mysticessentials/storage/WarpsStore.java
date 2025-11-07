package com.alphine.mysticessentials.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class WarpsStore {
    public static final class Warp {
        public String name, dim; public double x,y,z; public float yaw,pitch;
    }

    private final Map<String, Warp> warps = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public WarpsStore(Path cfgDir) { this.file = cfgDir.resolve("warps.json"); load(); }

    public synchronized void set(Warp w){ warps.put(w.name.toLowerCase(Locale.ROOT), w); save(); }
    public synchronized Optional<Warp> get(String name){ return Optional.ofNullable(warps.get(name.toLowerCase(Locale.ROOT))); }
    public synchronized boolean del(String name){ boolean ok = warps.remove(name.toLowerCase(Locale.ROOT))!=null; if(ok) save(); return ok; }
    public synchronized Set<String> names(){ return Set.copyOf(warps.keySet()); }
    public synchronized Collection<Warp> all(){ return List.copyOf(warps.values()); }

    public synchronized void load(){
        try {
            Files.createDirectories(file.getParent());
            if(!Files.exists(file)){ save(); return; }
            try(Reader r = Files.newBufferedReader(file)){
                var type = new com.google.gson.reflect.TypeToken<Map<String,Warp>>(){}.getType();
                Map<String,Warp> in = gson.fromJson(r,type);
                warps.clear(); if(in!=null) warps.putAll(in);
            }
        } catch (Exception ignored) {}
    }
    public synchronized void save(){
        try(Writer w = Files.newBufferedWriter(file)){
            gson.toJson(warps, w);
        } catch (Exception ignored) {}
    }
}
