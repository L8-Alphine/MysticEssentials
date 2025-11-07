package com.alphine.mysticessentials.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class SpawnStore {
    public static final class Point { public String dim; public double x,y,z; public float yaw,pitch; }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private Point spawn;

    public SpawnStore(Path cfgDir){ this.file = cfgDir.resolve("spawn.json"); load(); }
    public synchronized void set(Point p){ spawn=p; save(); }
    public synchronized Point get(){ return spawn; }

    public synchronized void load(){
        try {
            Files.createDirectories(file.getParent());
            if(!Files.exists(file)){ save(); return; }
            try(Reader r = Files.newBufferedReader(file)){ spawn = gson.fromJson(r, Point.class); }
        } catch (Exception ignored) {}
    }
    public synchronized void save(){
        try(Writer w = Files.newBufferedWriter(file)){ gson.toJson(spawn, w); }
        catch (Exception ignored) {}
    }
}
