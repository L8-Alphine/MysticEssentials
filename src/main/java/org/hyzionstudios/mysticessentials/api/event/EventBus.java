package org.hyzionstudios.mysticessentials.api.event;

import java.util.function.Consumer;

/**
 * Lightweight synchronous publish/subscribe bus for cross-module and addon
 * communication. Listeners are invoked on the calling thread in registration
 * order; a throwing listener is logged and does not stop the others.
 */
public interface EventBus {

    /**
     * Subscribes to all events of {@code type} (and its subtypes).
     *
     * @return a handle that unsubscribes when {@link Subscription#close()} is called.
     */
    <T extends MysticEvent> Subscription subscribe(Class<T> type, Consumer<T> listener);

    /**
     * Publishes an event to all matching listeners.
     *
     * @return the same event instance (useful for reading back cancellation state).
     */
    <T extends MysticEvent> T publish(T event);

    /** Handle returned by {@link #subscribe}, used to remove the listener. */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
