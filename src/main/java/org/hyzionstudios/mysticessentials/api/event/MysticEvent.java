package org.hyzionstudios.mysticessentials.api.event;

/**
 * Marker base type for events published on the Mystic Essentials {@link EventBus}.
 *
 * <p>This is an internal, cross-module event channel and is intentionally
 * separate from the Hytale server event system (which the platform layer bridges
 * into). Modules and addons publish and subscribe here to communicate through
 * events rather than direct implementation coupling.</p>
 */
public interface MysticEvent {

    /** Cancellable Mystic events implement this so listeners can veto them. */
    interface Cancellable extends MysticEvent {
        boolean isCancelled();

        void setCancelled(boolean cancelled);
    }
}
