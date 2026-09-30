package org.hyzionstudios.mysticessentials.integration.mysticidentity;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Keeps MysticEssentials registered with the MysticIdentity player portal. Started by name from
 * {@code core.integration.PortalBridge} once MysticIdentity is known to be on the classpath.
 *
 * <p>MysticIdentity publishes its API during its own startup, whose order against ours is not
 * guaranteed, so registration is retried every few seconds until it holds — and again if
 * MysticIdentity restarts and its registry goes with it.
 */
public final class EssentialsPortal implements AutoCloseable {

    private static final long ATTACH_INTERVAL_SECONDS = 5L;

    private final MysticCore core;
    private final ScheduledExecutorService attacher;
    private volatile PortalRegistration registration;
    private volatile boolean closed;

    private EssentialsPortal(MysticCore core) {
        this.core = core;
        this.attacher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mysticessentials-portal");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Called reflectively by {@code PortalBridge}. */
    public static AutoCloseable start(MysticCore core) {
        EssentialsPortal portal = new EssentialsPortal(core);
        portal.attacher.scheduleWithFixedDelay(portal::tryAttach, 0L, ATTACH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        return portal;
    }

    private void tryAttach() {
        if (closed) {
            return;
        }
        try {
            PortalRegistration current = registration;
            if (current != null && current.stillRegistered()) {
                return;
            }
            if (current != null) {
                current.close();
                registration = null;
            }
            PortalRegistration attached = PortalRegistration.attach(core);
            if (attached == null) {
                return;
            }
            registration = attached;
            core.log(Level.INFO, "Registered with the MysticIdentity player portal.");
        } catch (LinkageError | RuntimeException notReady) {
            // Not published yet, or an API build without the portal; try again on the next tick.
        }
    }

    @Override
    public void close() {
        closed = true;
        attacher.shutdownNow();
        PortalRegistration current = registration;
        registration = null;
        if (current != null) {
            current.close();
        }
    }
}
