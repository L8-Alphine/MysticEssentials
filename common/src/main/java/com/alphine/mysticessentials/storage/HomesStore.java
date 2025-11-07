package com.alphine.mysticessentials.storage;

import java.util.*;

public final class HomesStore {

    /** Keep the old API type name, but it's just a DTO we convert to/from PlayerDataStore.Home */
    public static final class Home {
        public String name;
        public String dim;
        public double x, y, z;
        public float yaw, pitch;
    }

    private final PlayerDataStore store;

    public HomesStore(PlayerDataStore store) {
        this.store = store;
    }

    // --- compatibility no-ops so existing callsites compile ---
    public void load() {}
    public void save() {}

    private static PlayerDataStore.Home toPD(Home h) {
        PlayerDataStore.Home p = new PlayerDataStore.Home();
        p.name = h.name;
        p.dim  = h.dim;
        p.x = h.x; p.y = h.y; p.z = h.z;
        p.yaw = h.yaw; p.pitch = h.pitch;
        return p;
    }
    private static Home fromPD(PlayerDataStore.Home p) {
        Home h = new Home();
        h.name = p.name;
        h.dim  = p.dim;
        h.x = p.x; h.y = p.y; h.z = p.z;
        h.yaw = p.yaw; h.pitch = p.pitch;
        return h;
    }

    public synchronized void set(UUID id, Home h) {
        store.setHome(id, toPD(h));
    }

    public synchronized Optional<Home> get(UUID id, String name) {
        return store.getHome(id, name).map(HomesStore::fromPD);
    }

    public synchronized boolean delete(UUID id, String name) {
        return store.delHome(id, name);
    }

    public synchronized Collection<Home> all(UUID id) {
        return store.allHomes(id).stream().map(HomesStore::fromPD).toList();
    }

    public synchronized Set<String> names(UUID id) {
        return store.homeNames(id);
    }
}
