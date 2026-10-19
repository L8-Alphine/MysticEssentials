package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The open editor sessions, one per player. Starting a session ends the
 * player's previous one, so a page left over from an older editor sees
 * {@link EditorSession#active()} turn false. The module ends a player's
 * session on disconnect and clears them all when it disables. Engine-free.
 */
public final class EditorSessions {

    private final ConcurrentMap<UUID, EditorSession<?>> sessions = new ConcurrentHashMap<>();

    /**
     * Starts editing {@code draftId} for {@code player}, ending any session
     * they already had.
     *
     * @param loadedHash the draft file's content hash, or {@code null} for a new draft
     */
    public <T> EditorSession<T> start(UUID player, String draftId, Class<T> type, T working, String loadedHash) {
        EditorSession<T> session = new EditorSession<>(player, draftId, type, working, loadedHash);
        EditorSession<?> previous = sessions.put(player, session);
        if (previous != null) {
            previous.end();
        }
        return session;
    }

    /** The player's open session, if any. */
    public Optional<EditorSession<?>> get(UUID player) {
        return Optional.ofNullable(sessions.get(player));
    }

    /** The player's open session when it edits a {@code type}. */
    public <T> Optional<EditorSession<T>> get(UUID player, Class<T> type) {
        EditorSession<?> session = sessions.get(player);
        return session == null ? Optional.empty() : session.as(type);
    }

    /** Players other than {@code except} with {@code draftId} open, for an "also being edited by" note. */
    public List<UUID> editing(Class<?> type, String draftId, UUID except) {
        List<UUID> players = new ArrayList<>();
        sessions.forEach((player, session) -> {
            if (session.type() == type && session.draftId().equals(draftId) && !player.equals(except)) {
                players.add(player);
            }
        });
        return players;
    }

    /** Ends the player's session (they disconnected). */
    public void end(UUID player) {
        EditorSession<?> session = sessions.remove(player);
        if (session != null) {
            session.end();
        }
    }

    /**
     * Ends {@code session} only while it is still its owner's current one, so a
     * page closing its own editor cannot end a newer session.
     *
     * @return {@code true} when it was current and is now ended
     */
    public boolean end(EditorSession<?> session) {
        if (sessions.remove(session.owner(), session)) {
            session.end();
            return true;
        }
        return false;
    }

    /** Ends every session (the module is disabling). */
    public void clear() {
        for (UUID player : new ArrayList<>(sessions.keySet())) {
            end(player);
        }
    }

    public int size() {
        return sessions.size();
    }
}
