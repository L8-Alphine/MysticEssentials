package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One player's open editor: the working copy of a pool or key draft, plus the
 * page state that has to outlive a rebuild. Hytale pages are rebuilt by opening
 * a fresh page instance, so nothing on a page object survives a button press;
 * everything the editor remembers lives here. Engine-free.
 *
 * <p>The working copy is edited in memory and reaches disk only through an
 * explicit save. {@link #loadedHash()} is the draft file's content hash when
 * it was loaded ({@code null} for a draft that has never been saved); the
 * draft store compares it on save so a second editor's changes are never
 * overwritten silently (P2-D2).</p>
 *
 * <p>A session ends when the player disconnects, starts another editor, or the
 * module disables. Pages check {@link #active()} on every press, so a stale
 * page cannot write into a session that has ended. Pages run on the player's
 * world thread but a save completes on the async executor, so the mutable
 * state is guarded by the session's monitor.</p>
 *
 * @param <T> the draft type, {@code LootBoxDraft} or {@code KeyDraft}
 */
public final class EditorSession<T> {

    private final UUID owner;
    private final String draftId;
    private final Class<T> type;
    private final Map<String, String> inputs = new LinkedHashMap<>();
    private volatile boolean active = true;
    private T working;
    private String loadedHash;
    private boolean dirty;
    private long changes;
    private String tab;
    private int selectedRow = -1;
    private Consumer<String> pendingReturn;

    EditorSession(UUID owner, String draftId, Class<T> type, T working, String loadedHash) {
        this.owner = owner;
        this.draftId = draftId;
        this.type = type;
        this.working = working;
        this.loadedHash = loadedHash;
    }

    public UUID owner() {
        return owner;
    }

    public String draftId() {
        return draftId;
    }

    public Class<T> type() {
        return type;
    }

    /** This session viewed as one over {@code wanted}, when that is its draft type. */
    @SuppressWarnings("unchecked")
    public <U> Optional<EditorSession<U>> as(Class<U> wanted) {
        return wanted == type ? Optional.of((EditorSession<U>) this) : Optional.empty();
    }

    /** {@code false} once the session has ended; a page must not touch it after that. */
    public boolean active() {
        return active;
    }

    void end() {
        active = false;
        synchronized (this) {
            pendingReturn = null;
        }
    }

    // ----- Working copy -------------------------------------------------------

    /** The draft being edited. Edit it in place, then call {@link #markDirty()}. */
    public synchronized T working() {
        return working;
    }

    /** The draft file's content hash when it was loaded or last saved; {@code null} for a new draft. */
    public synchronized String loadedHash() {
        return loadedHash;
    }

    /** @return {@code true} when the draft has never been saved to a file. */
    public synchronized boolean isNew() {
        return loadedHash == null;
    }

    /** @return {@code true} when the working copy differs from what was loaded or last saved. */
    public synchronized boolean dirty() {
        return dirty;
    }

    /** A counter that moves on every edit; pass it to {@link #markSaved} so a save cannot hide a later edit. */
    public synchronized long changes() {
        return changes;
    }

    /** Records an edit to the working copy. */
    public synchronized void markDirty() {
        dirty = true;
        changes++;
    }

    /**
     * A save of the working copy as it stood at {@code changesAtSave} finished,
     * and the file now hashes to {@code savedHash}. Edits made while the save
     * ran keep the session dirty.
     */
    public synchronized void markSaved(String savedHash, long changesAtSave) {
        loadedHash = savedHash;
        if (changes == changesAtSave) {
            dirty = false;
        }
    }

    /**
     * Replaces the working copy with a fresh load (Reload after a conflict, or
     * Revert to saved): clean, and with no unsaved input or pending sub-page.
     * The tab and selected row are kept; the page clamps the row.
     */
    public synchronized void reload(T fresh, String hash) {
        working = fresh;
        loadedHash = hash;
        dirty = false;
        changes++;
        inputs.clear();
        pendingReturn = null;
    }

    // ----- Page state ---------------------------------------------------------

    /** The selected tab's id, or {@code null} for the page's first tab. */
    public synchronized String tab() {
        return tab;
    }

    public synchronized void tab(String tabId) {
        tab = tabId;
    }

    /** The selected row (a reward, a key), or {@code -1} for none. */
    public synchronized int selectedRow() {
        return selectedRow;
    }

    public synchronized void selectRow(int row) {
        selectedRow = row < 0 ? -1 : row;
    }

    // ----- Unsaved input ------------------------------------------------------

    /**
     * Remembers what the player typed, keyed by form field, so the next build
     * shows it again even when it does not parse yet ("12a" in a number field).
     */
    public synchronized void rememberInputs(Map<String, String> values) {
        inputs.putAll(values);
    }

    /** The player's unsaved text for {@code key}, or {@code null} when there is none. */
    public synchronized String input(String key) {
        return inputs.get(key);
    }

    /** A copy of every remembered input. */
    public synchronized Map<String, String> inputs() {
        return new LinkedHashMap<>(inputs);
    }

    /** Drops the remembered text for {@code key} once it has been applied to the working copy. */
    public synchronized void forgetInput(String key) {
        inputs.remove(key);
    }

    public synchronized void forgetInputs() {
        inputs.clear();
    }

    // ----- Sub-pages ----------------------------------------------------------

    /**
     * Arms the action a sub-page (the item picker) hands its result to. It runs
     * at most once; arming again replaces an earlier one that never returned.
     */
    public synchronized void awaitReturn(Consumer<String> action) {
        pendingReturn = action;
    }

    /** @return {@code true} while a sub-page is open and has not returned. */
    public synchronized boolean awaitingReturn() {
        return pendingReturn != null;
    }

    /**
     * Runs the pending action with {@code value} and marks the copy dirty.
     *
     * @return {@code false} when nothing was pending (the sub-page is stale) or
     *         the session has ended
     */
    public synchronized boolean completeReturn(String value) {
        Consumer<String> action = pendingReturn;
        pendingReturn = null;
        if (action == null || !active) {
            return false;
        }
        action.accept(value);
        markDirty();
        return true;
    }

    /** Drops the pending action without running it. @return {@code true} when one was pending. */
    public synchronized boolean cancelReturn() {
        boolean had = pendingReturn != null;
        pendingReturn = null;
        return had;
    }
}
