package org.hyzionstudios.mysticessentials.api.ui;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Creates and cleans up per-player UI sessions. */
public final class UiSessionManager {
    private final ConcurrentHashMap<UUID, UiSession> sessions = new ConcurrentHashMap<>();

    public UiSession open(UUID playerId) {
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        return sessions.computeIfAbsent(playerId, UiSession::new);
    }

    public Optional<UiSession> find(UUID playerId) { return Optional.ofNullable(sessions.get(playerId)); }
    public void close(UUID playerId) { if (playerId != null) sessions.remove(playerId); }
    public Collection<UiSession> active() { return List.copyOf(sessions.values()); }
    public int size() { return sessions.size(); }
    public void clear() { sessions.clear(); }
}
