package com.alphine.mysticessentials.teleport;

import java.util.*;

public class TpaManager {
    public static final class Req { public final UUID from,to; public final long expiry; public Req(UUID f,UUID t,long e){from=f;to=t;expiry=e;} }
    private final Map<UUID, Req> incoming = new HashMap<>();
    public void request(UUID from, UUID to, long ttlSec){ incoming.put(to, new Req(from,to,System.currentTimeMillis()+ttlSec*1000L)); }
    public Optional<Req> peek(UUID to){
        var r = incoming.get(to); if(r==null || System.currentTimeMillis()>r.expiry){ incoming.remove(to); return Optional.empty(); }
        return Optional.of(r);
    }
    public Optional<Req> consume(UUID to){
        var r = incoming.remove(to); if(r==null || System.currentTimeMillis()>r.expiry) return Optional.empty(); return Optional.of(r);
    }
    public void clear(UUID to){ incoming.remove(to); }
}
