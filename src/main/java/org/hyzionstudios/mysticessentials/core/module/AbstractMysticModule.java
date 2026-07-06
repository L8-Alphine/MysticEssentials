package org.hyzionstudios.mysticessentials.core.module;

import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.MysticEssentialsAPI;
import org.hyzionstudios.mysticessentials.api.module.MysticModule;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;

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
        core.platform().registerCommand(command);
    }

    protected void log(String message) {
        core.log(Level.INFO, "[" + id + "] " + message);
    }
}
