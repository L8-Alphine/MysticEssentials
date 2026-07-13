package org.hyzionstudios.mysticessentials.modules.playervaults.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;

/** Fired after vault data is loaded from cache or permanent storage. */
public final class PlayerVaultLoadEvent implements MysticEvent {

    /** Where the loaded copy came from. */
    public enum Source {
        CACHE, STORAGE, NEW
    }

    private final PlayerVault vault;
    private final Source source;

    public PlayerVaultLoadEvent(PlayerVault vault, Source source) {
        this.vault = vault;
        this.source = source;
    }

    public PlayerVault getVault() {
        return vault;
    }

    public Source getSource() {
        return source;
    }
}
