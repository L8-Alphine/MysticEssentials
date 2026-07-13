package org.hyzionstudios.mysticessentials.modules.patchnotes;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-player read tracking, stored through the {@link
 * org.hyzionstudios.mysticessentials.api.service.StorageService} under the
 * {@code patchnotes} namespace keyed by player UUID. Records which patch ids the
 * player has seen so the list can show unread indicators and the join
 * notification can count only new notes.
 */
public final class PatchReadState {

    public String playerUuid;
    public List<String> readPatchIds = new ArrayList<>();
    /** ISO-8601 instant the player last opened the UI (informational). */
    public String lastOpened;

    public PatchReadState() {
    }

    public PatchReadState(String playerUuid) {
        this.playerUuid = playerUuid;
    }

    public boolean isRead(String patchId) {
        return patchId != null && readPatchIds != null && readPatchIds.contains(patchId);
    }

    /** Marks a patch read. @return {@code true} if this changed the state. */
    public boolean markRead(String patchId) {
        if (patchId == null || patchId.isBlank()) {
            return false;
        }
        if (readPatchIds == null) {
            readPatchIds = new ArrayList<>();
        }
        if (readPatchIds.contains(patchId)) {
            return false;
        }
        readPatchIds.add(patchId);
        return true;
    }
}
