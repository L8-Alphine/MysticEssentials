package org.hyzionstudios.mysticessentials.core.module;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.MysticEssentialsAPI;
import org.hyzionstudios.mysticessentials.api.module.MysticModule;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;

import com.hypixel.hytale.event.IAsyncEvent;
import com.hypixel.hytale.event.IBaseEvent;
import com.hypixel.hytale.registry.Registration;
import com.hypixel.hytale.server.core.command.system.CommandRegistration;

/**
 * Convenience base for feature modules. Handles id/name/version boilerplate,
 * captures the Core handle on load, and offers helpers for command registration
 * and logging so subclasses focus on behaviour.
 */
public abstract class AbstractMysticModule implements MysticModule {

    private final String id;
    private final String name;
    private final String version;

    protected MysticCore core;

    /** Engine handles for commands this module registered, so they can be dropped on disable. */
    private final List<CommandRegistration> commandRegistrations = new ArrayList<>();

    /** Engine handles for event listeners this module registered, so they can be dropped on disable. */
    private final List<Registration> eventRegistrations = new ArrayList<>();

    protected AbstractMysticModule(String id, String name, String version) {
        this.id = id;
        this.name = name;
        this.version = version;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final String version() {
        return version;
    }

    @Override
    public void onLoad(MysticEssentialsAPI api) {
        this.core = (MysticCore) api;
    }

    @Override
    public void onReload() {
        // Modules with reloadable config override this.
    }

    // ----- Helpers -----------------------------------------------------------

    protected void registerCommand(MysticCommand command) {
        CommandRegistration registration = core.platform().registerCommand(command);
        if (registration != null) {
            commandRegistrations.add(registration);
        }
    }

    /**
     * Unregisters every command this module registered through
     * {@link #registerCommand}. Called by the module manager when the module is
     * disabled at runtime, so hot-disable/enable does not leave dead or duplicate
     * commands behind. Idempotent — the engine handles are one-shot.
     */
    public final void unregisterCommands() {
        for (CommandRegistration registration : commandRegistrations) {
            try {
                registration.unregister();
            } catch (Throwable ignored) {
                // One-shot handle; already gone or engine shutting down.
            }
        }
        commandRegistrations.clear();
    }

    /** Registers an event listener for this module, tracked so it can be dropped on disable. */
    protected <E extends IBaseEvent<Void>> void registerEvent(Class<? super E> eventType, Consumer<E> listener) {
        Registration registration = core.platform().onEvent(eventType, listener);
        if (registration != null) {
            eventRegistrations.add(registration);
        }
    }

    /** Registers an async event listener for this module, tracked so it can be dropped on disable. */
    protected <K, E extends IAsyncEvent<K>> void registerAsyncEvent(Class<? super E> eventType,
            Function<CompletableFuture<E>, CompletableFuture<E>> handler) {
        Registration registration = core.platform().onAsyncEvent(eventType, handler);
        if (registration != null) {
            eventRegistrations.add(registration);
        }
    }

    /**
     * Unregisters every event listener this module registered through
     * {@link #registerEvent}/{@link #registerAsyncEvent}. Called by the module
     * manager when the module is disabled at runtime, so hot-disable/enable does
     * not leave dead listeners reacting or duplicate listeners stacking up.
     * Idempotent — the engine handles are one-shot.
     */
    public final void unregisterEventListeners() {
        for (Registration registration : eventRegistrations) {
            try {
                registration.unregister();
            } catch (Throwable ignored) {
                // One-shot handle; already gone or engine shutting down.
            }
        }
        eventRegistrations.clear();
    }

    protected void log(String message) {
        core.log(Level.INFO, "[" + id + "] " + message);
    }
}
