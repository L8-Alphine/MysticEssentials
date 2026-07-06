package org.hyzionstudios.mysticessentials.core.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.event.EventBus;
import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Default {@link EventBus}: synchronous, thread-safe, dispatched in registration
 * order. A listener that throws is logged and skipped so one bad subscriber
 * cannot break the publish.
 */
public final class SimpleEventBus implements EventBus {

    private record Registration(Class<?> type, Consumer<?> listener) {
    }

    private final MysticCore core;
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();

    public SimpleEventBus(MysticCore core) {
        this.core = core;
    }

    @Override
    public <T extends MysticEvent> Subscription subscribe(Class<T> type, Consumer<T> listener) {
        Registration registration = new Registration(type, listener);
        registrations.add(registration);
        return () -> registrations.remove(registration);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends MysticEvent> T publish(T event) {
        for (Registration registration : registrations) {
            if (registration.type().isInstance(event)) {
                try {
                    ((Consumer<T>) registration.listener()).accept(event);
                } catch (Throwable t) {
                    core.log(Level.SEVERE, "Event listener for " + registration.type().getSimpleName()
                            + " threw: " + t);
                }
            }
        }
        return event;
    }
}
