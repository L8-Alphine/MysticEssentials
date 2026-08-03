package org.hyzionstudios.mysticessentials.core.license;

import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.mysticlicensing.license.LicenseGate;
import com.mysticlicensing.license.LicenseLog;
import com.mysticlicensing.license.Products;

/**
 * Wires {@code mystic-license-core} to this mod. The whole adapter is the three
 * integration points the library asks for: a data directory, a server identity
 * and a logger.
 *
 * <h2>What licensing may and may not do here</h2>
 * A licensing failure switches off the licensed modules listed in
 * {@link com.mysticlicensing.license.Products.Essentials} and changes nothing
 * else. Mystic Essentials loads, every unlicensed module enables normally, and
 * the server starts. There is no code path in which a missing, expired or
 * corrupt {@code license.mclicense} prevents the mod or the server from
 * running - see {@code mystic-license-core/README.md}.
 *
 * <p>The license file belongs next to the mod's own config, at
 * {@code mods/MysticEssentials/license.mclicense}. The server's licensing id is
 * generated on first run and kept in {@code server-id.txt} beside it; that is
 * the value an operator registers in the portal.
 */
public final class LicenseSupport {

    /** File name operators are told to drop into the data directory. */
    public static final String LICENSE_FILE = LicenseGate.LICENSE_FILE;

    private LicenseSupport() {
    }

    /**
     * Build the gate. Does not verify anything yet - call {@link LicenseGate#start()}
     * once the mod is far enough along to log.
     */
    public static LicenseGate create(MysticCore core) {
        return LicenseGate.builder(Products.ESSENTIALS)
                .dataDir(core.paths().root())
                .modVersion(core.getVersion())
                .logger(adapt(core))
                .build();
    }

    /** Routes the library's two log levels onto the plugin's Hytale logger. */
    private static LicenseLog adapt(MysticCore core) {
        return new LicenseLog() {
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
