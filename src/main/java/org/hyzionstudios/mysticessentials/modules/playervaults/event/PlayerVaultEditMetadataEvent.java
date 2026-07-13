package org.hyzionstudios.mysticessentials.modules.playervaults.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/**
 * Fired when a vault's card metadata (name, color, icon, description) is about to
 * change. Cancellable so moderation/naming systems can veto a value.
 */
public final class PlayerVaultEditMetadataEvent implements MysticEvent.Cancellable {

    /** Which metadata field is changing. */
    public enum Field {
        NAME, COLOR, ICON, DESCRIPTION, RESET
    }

    private final UUID editorUuid;
    private final UUID ownerUuid;
    private final int vaultNumber;
    private final Field field;
    private final String oldValue;
    private String newValue;
    private boolean cancelled;

    public PlayerVaultEditMetadataEvent(UUID editorUuid, UUID ownerUuid, int vaultNumber,
            Field field, String oldValue, String newValue) {
        this.editorUuid = editorUuid;
        this.ownerUuid = ownerUuid;
        this.vaultNumber = vaultNumber;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public UUID getEditorUuid() {
        return editorUuid;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public int getVaultNumber() {
        return vaultNumber;
    }

    public Field getField() {
        return field;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    /** Lets a listener rewrite (e.g. sanitize) the value before it is applied. */
    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
