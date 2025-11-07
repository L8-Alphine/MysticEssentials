package com.alphine.mysticessentials.util;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class GodService {
    private final Set<UUID> god = new HashSet<>();
    public boolean toggle(UUID id){ if(god.contains(id)){ god.remove(id); return false; } god.add(id); return true; }
    public boolean isGod(UUID id){ return god.contains(id); }
}
