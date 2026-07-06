package org.hyzionstudios.mysticessentials;

import java.util.logging.Level;

import javax.annotation.Nonnull;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

/**
 * Mystic Essentials plugin entry point.
 *
 * <p>Thin adapter over the verified Hytale {@link JavaPlugin} lifecycle
 * ({@code setup} / {@code start} / {@code shutdown}); all real work lives in
 * {@link MysticCore}, the non-disableable Core that owns every shared service
 * and drives module loading.</p>
 *
 * <p>Do not rename this class or its package: the Hytale mod manifest
 * ({@code manifest.json}) references {@code Main} =
 * {@code org.hyzionstudios.mysticessentials.MysticessentialsPlugin}.</p>
 */
public class MysticessentialsPlugin extends JavaPlugin {

    private MysticCore core;

    public MysticessentialsPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("MysticEssentials starting...");
        try {
            core = new MysticCore(this);
            core.enable();
        } catch (Throwable t) {
            getLogger().at(Level.SEVERE).log("MysticEssentials failed to start: " + t);
        }
    }

    @Override
    protected void shutdown() {
        if (core != null) {
            try {
                core.disable();
            } catch (Throwable t) {
                getLogger().at(Level.SEVERE).log("Error during MysticEssentials shutdown: " + t);
            }
            core = null;
        }
        getLogger().at(Level.INFO).log("MysticEssentials shut down.");
    }
}
