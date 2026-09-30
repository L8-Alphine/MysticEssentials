package org.hyzionstudios.mysticessentials.core.integration;

import java.util.logging.Level;
import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Starts MysticEssentials' pages on the MysticIdentity web portal (Player Portal bible §8):
 * mail, read-only vaults and patch notes, plus a My identity card.
 *
 * <p>Like every Mystic bridge here, nothing in the main source set names a MysticIdentity type
 * (see {@code IntegrationContractTest}). A portal provider has to <em>implement</em>
 * MysticIdentity's interface, though, so the adapter lives in its own source set
 * ({@code src/mysticidentity/java}), compiled against the MysticIdentity API and bundled into the
 * mod jar; this bridge loads it by name, and only once MysticIdentity itself is on the classpath.
 * Honours {@code integrations.mysticIdentity} and follows it across {@code /mystic reload}.
 */
public final class PortalBridge {

    private static final String PROVIDER_CLASS = "org.hyzionstudios.mysticidentity.MysticIdentityProvider";
    private static final String ADAPTER_CLASS =
            "org.hyzionstudios.mysticessentials.integration.mysticidentity.EssentialsPortal";

    private final MysticCore core;
    private AutoCloseable adapter;

    public PortalBridge(MysticCore core) {
        this.core = core;
    }

    public synchronized void init(boolean enabled) {
        close();
        if (!enabled) {
            return;
        }
        ClassLoader loader = PortalBridge.class.getClassLoader();
        try {
            Class.forName(PROVIDER_CLASS, false, loader);
        } catch (ClassNotFoundException | LinkageError absent) {
            return;
        }
        try {
            Class<?> type = Class.forName(ADAPTER_CLASS, true, loader);
            adapter = (AutoCloseable) type.getMethod("start", MysticCore.class).invoke(null, core);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
            core.log(Level.WARNING, "MysticIdentity portal pages are unavailable: " + failure);
        }
    }

    public synchronized void close() {
        AutoCloseable current = adapter;
        adapter = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (Exception ignored) {
            // Shutting down; the registry goes with MysticIdentity either way.
        }
    }
}
