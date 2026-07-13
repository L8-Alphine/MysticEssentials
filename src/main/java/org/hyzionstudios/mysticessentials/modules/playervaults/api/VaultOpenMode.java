package org.hyzionstudios.mysticessentials.modules.playervaults.api;

/**
 * How a vault UI session is opened. Determines whether an edit lock is taken and
 * whether item interactions are permitted.
 */
public enum VaultOpenMode {

    /** The owner opening their own vault. Takes an edit lock; items mutable. */
    PLAYER(true, false),
    /** Staff editing another player's vault. Takes an edit lock; items mutable. */
    ADMIN_EDIT(true, true),
    /** Staff inspecting a vault without mutating items. Slots are locked; may open while locked elsewhere. */
    ADMIN_READONLY(false, true);

    private final boolean mutable;
    private final boolean admin;

    VaultOpenMode(boolean mutable, boolean admin) {
        this.mutable = mutable;
        this.admin = admin;
    }

    /** @return {@code true} if item interactions are allowed (an edit lock is required). */
    public boolean isMutable() {
        return mutable;
    }

    /** @return {@code true} if this is a staff-initiated open against another player's vault. */
    public boolean isAdmin() {
        return admin;
    }

    public boolean isReadOnly() {
        return !mutable;
    }

    /** Parses a config/command mode string, defaulting to {@link #ADMIN_EDIT} for admin contexts. */
    public static VaultOpenMode adminFrom(String raw) {
        if (raw != null && raw.equalsIgnoreCase("READ_ONLY")) {
            return ADMIN_READONLY;
        }
        return ADMIN_EDIT;
    }
}
