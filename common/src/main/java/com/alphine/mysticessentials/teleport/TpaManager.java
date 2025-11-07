package com.alphine.mysticessentials.teleport;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TpaManager {
    public static final class Req { public final UUID from,to; public final long expiry; public Req(UUID f, UUID t, long e){ from=f; to=t; expiry=e; } }

    // Concurrent to avoid any surprises if something ever touches this off-thread.
    private final Map<UUID, Req> incoming = new ConcurrentHashMap<>();

    public void request(UUID from, UUID to, long ttlSec){
        long expiryAt = System.currentTimeMillis() + Math.max(1, ttlSec) * 1000L;
        incoming.put(to, new Req(from, to, expiryAt));
    }

    public Optional<Req> peek(UUID to){
        var r = incoming.get(to);
        if (r == null) return Optional.empty();
        if (System.currentTimeMillis() > r.expiry) { incoming.remove(to); return Optional.empty(); }
        return Optional.of(r);
    }

    public Optional<Req> consume(UUID to){
        var r = incoming.remove(to);
        if (r == null) return Optional.empty();
        if (System.currentTimeMillis() > r.expiry) return Optional.empty();
        return Optional.of(r);
    }

    public void clear(UUID to){ incoming.remove(to); }
}
