package com.alphine.mysticessentials.storage;

import java.util.*;

public final class KitPlayerStore {

    public static final class PlayerData {
        public final Map<String, Long> lastClaim;
        public final Set<String> usedOnce;
        PlayerData(Map<String, Long> lc, Set<String> uo) {
            this.lastClaim = lc;
            this.usedOnce  = uo;
        }
    }

    private final PlayerDataStore store;

    public KitPlayerStore(PlayerDataStore store){
        this.store = store;
    }

    // optional snapshot view (read-only)
    public synchronized PlayerData get(UUID uuid){
        Map<String, Long> lc = store.getAllKitLast(uuid);      // accessor below
        Set<String> uo      = store.getAllKitsUsedOnce(uuid);  // accessor below
        return new PlayerData(
                Collections.unmodifiableMap(new HashMap<>(lc)),
                Collections.unmodifiableSet(new HashSet<>(uo))
        );
    }

    public synchronized long getLast(UUID uuid, String kit){
        return store.getKitLast(uuid, kit);
    }

    public synchronized void setLast(UUID uuid, String kit, long when){
        store.setKitLast(uuid, kit, when);
    }

    public synchronized boolean hasUsedOnce(UUID uuid, String kit){
        return store.hasUsedKitOnce(uuid, kit);
    }

    public synchronized void markUsedOnce(UUID uuid, String kit){
        store.markKitUsedOnce(uuid, kit);
    }
}
