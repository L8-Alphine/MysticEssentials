package com.mysticlicensing.example;

import com.mysticlicensing.license.LicenseGate;
import com.mysticlicensing.license.LicenseLog;
import com.mysticlicensing.license.LicenseStatus;
import com.mysticlicensing.license.MysticLicenseService;
import com.mysticlicensing.license.NoopMysticLicenseService;
import com.mysticlicensing.license.Products;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A worked example of wiring the license gate into a mod, with no Hytale types
 * in sight.
 *
 * <p>This module exists to keep the core honest. It depends on
 * {@code mystic-license-core}; the core does not depend on it, and it never
 * will, which is what makes the core testable without a game server. The three
 * integration points a real mod has to supply are the only things this class
 * fills in:
 *
 * <ol>
 *   <li><b>Where the license file lives</b> - here, the mod's data directory.</li>
 *   <li><b>The server UUID</b> - here, left to {@link LicenseGate}'s own
 *       persisted identity. A mod with a real server id passes
 *       {@code .serverUuid(...)} instead.</li>
 *   <li><b>Logging</b> - a three-line adapter over the host's logger.</li>
 * </ol>
 *
 * <p>For the MysticEssentials integration against real Hytale types, see
 * {@code org.hyzionstudios.mysticessentials.core.license.LicenseSupport} and
 * INTEGRATION.md.
 */
public final class ExampleModLicensing {

    private static final String MOD_VERSION = "1.0.0";

    private final LicenseGate gate;

    /**
     * @param dataDir the mod's own data directory, writable, where
     *                {@code license.mclicense} and {@code server-id.txt} live
     */
    public ExampleModLicensing(Logger hostLogger, Path dataDir) {
        this.gate = LicenseGate.builder(Products.GUILDS)
                .dataDir(dataDir)
                .modVersion(MOD_VERSION)
                .logger(adapt(hostLogger))
                .build();
    }

    /**
     * Call once during mod initialisation, before registering anything gated.
     *
     * <p>Note what this method does <em>not</em> do: it does not return a
     * failure the caller has to handle, it does not throw, and the caller does
     * not branch on the status. A licensing problem is already fully expressed
     * by {@link #license()} answering {@code false}.
     */
    public void start() {
        LicenseStatus status = gate.start();

        // Gate the licensed module and nothing else. Everything unlicensed in
        // this mod registers below, unconditionally, whatever `status` says.
        gate.whenLicensed(Products.Guilds.MODULE_NPC_GUARDS, this::registerGuardNpcModule);

        registerAlwaysAvailableFeatures(status);
    }

    /** What feature code depends on. Never the concrete gate. */
    public MysticLicenseService license() {
        return gate;
    }

    /** Wire this to an admin command so a renewed license needs no restart. */
    public String reload() {
        gate.reload();
        return gate.summaryLine();
    }

    private void registerGuardNpcModule() {
        // The licensed feature. Only reached when the license grants it.
    }

    private void registerAlwaysAvailableFeatures(LicenseStatus status) {
        // Deliberately ignores `status`. An unpaid or broken license must never
        // switch off functionality the operator did not have to pay for.
    }

    /**
     * The whole logging adapter. Two methods, no framework, no configuration.
     */
    private static LicenseLog adapt(Logger logger) {
        return new LicenseLog() {
            @Override
            public void info(String message) {
                logger.log(Level.INFO, message);
            }

            @Override
            public void warn(String message) {
                logger.log(Level.WARNING, message);
            }
        };
    }

    /**
     * What an unlicensed build ships instead: the same call sites, nothing
     * granted, no crypto and no file access.
     */
    public static MysticLicenseService unlicensedBuild() {
        return NoopMysticLicenseService.INSTANCE;
    }
}
