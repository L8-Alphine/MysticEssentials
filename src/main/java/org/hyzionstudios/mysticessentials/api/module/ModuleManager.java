package org.hyzionstudios.mysticessentials.api.module;

import java.util.Collection;
import java.util.Optional;

/**
 * Loads, enables, disables, reloads, and validates modules and their
 * dependencies. Addons can register additional modules through
 * {@link #register(MysticModule)} before the Core finishes enabling, or through
 * {@link #registerExternalModule(MysticModule)} when they attach at runtime.
 */
public interface ModuleManager {

    /** Registers a module instance. Has no effect if a module with the same id already exists. */
    void register(MysticModule module);

    /**
     * Registers an addon-owned module and enables it immediately when the Core has
     * already completed its normal module startup. Explicit {@code false} entries in
     * {@code config.json -> modules} are still respected.
     */
    void registerExternalModule(MysticModule module);

    /** Compatibility alias for addon registrars that probe for this method name. */
    default void registerModule(MysticModule module) {
        registerExternalModule(module);
    }

    /** @return {@code true} if the module id is registered and currently enabled. */
    boolean isEnabled(String moduleId);

    /** @return {@code true} if the module id is registered (enabled or not). */
    boolean isRegistered(String moduleId);

    /** @return the registered module for the id, if any. */
    Optional<MysticModule> getModule(String moduleId);

    /** @return an immutable view of all registered modules. */
    Collection<MysticModule> getModules();

    /** Reloads a single enabled module. @return {@code true} if reloaded. */
    boolean reload(String moduleId);

    /** Reloads every enabled module. */
    void reloadAll();
}
