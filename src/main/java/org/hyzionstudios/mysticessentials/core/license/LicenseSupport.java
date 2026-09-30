package org.hyzionstudios.mysticessentials.core.license;

import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.mystic.licensing.LicenseGate;

/**
 * Wires the MysticLicenses v2 client to this mod: the data directory, the
 * version shown in the license portal, and the Hytale logger.
 *
 * <p>The key belongs next to the mod's own config, at
 * {@code mods/MysticEssentials/license.key}. See {@link EssentialsLicense} for
 * what a licensing problem may and may not do.
 */
public final class LicenseSupport {

    /** File name operators are told to put their key in. */
    public static final String KEY_FILE = LicenseGate.KEY_FILE;

    private LicenseSupport() {
    }

    /** Reads the key and activates; never throws. Call once the mod is far enough along to log. */
    public static EssentialsLicense start(MysticCore core) {
        return EssentialsLicense.start(core.paths().root(), core.getVersion(), adapt(core));
    }

    /** Routes the client's two log levels onto the plugin's Hytale logger. */
    private static LicenseGate.Log adapt(MysticCore core) {
        return new LicenseGate.Log() {
            @Override
            public void info(String message) {
                core.log(Level.INFO, message);
            }

            @Override
            public void warn(String message) {
                core.log(Level.WARNING, message);
            }
        };
    }
}
