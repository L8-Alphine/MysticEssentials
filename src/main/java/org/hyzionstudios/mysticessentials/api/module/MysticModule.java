package org.hyzionstudios.mysticessentials.api.module;

import java.util.Collections;
import java.util.List;

import org.hyzionstudios.mysticessentials.api.MysticEssentialsAPI;

/**
 * Contract every optional feature module implements.
 *
 * <p>Lifecycle ordering is driven by declared dependencies; see
 * {@link #hardDependencies()} and {@link #softDependencies()}. The recommended
 * startup order is: Core services, integrations, Teleportation, Spawn, Warps,
 * Mail, Announcements, AFK, Chat. Teleportation loads early because Spawn,
 * Homes, Warps, Player Warps, and AFK Rewards consume it.</p>
 */
public interface MysticModule {

    /** Stable, lowercase identifier used in config and dependency declarations (e.g. {@code "mail"}). */
    String id();

    /** Human-readable module name. */
    String name();

    /** Module version, independent of the Core version. */
    String version();

    /**
     * Called once, before {@link #onEnable()}, giving the module a handle to the
     * Core API. Implementations should resolve service references here but must
     * not touch other modules yet.
     */
    void onLoad(MysticEssentialsAPI api);

    /** Activates the module: register commands, events, tasks, and services. */
    void onEnable();

    /** Deactivates the module: unregister/flush and release resources. */
    void onDisable();

    /** Reloads the module's configuration and messages without a full restart. */
    void onReload();

    /** Modules that improve this one if present, but are not required. */
    default List<String> softDependencies() {
        return Collections.emptyList();
    }

    /** Modules that must be enabled for this one to function. */
    default List<String> hardDependencies() {
        return Collections.emptyList();
    }
}
