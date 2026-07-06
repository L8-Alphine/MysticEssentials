package org.hyzionstudios.mysticessentials.core.scheduler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Delayed and repeating task scheduling for the Core and modules.
 *
 * <p>Backed by a small {@link ScheduledExecutorService}. Callbacks run on a
 * Mystic background thread, which is appropriate for timers, cooldown expiry,
 * auto-broadcast rotation, and AFK checks.</p>
 *
 * <p><b>Threading note:</b> work that mutates world or entity state (e.g. the
 * actual teleport move) must be dispatched onto the owning world's thread via
 * {@code platform.HytalePlatform#runOnWorld}, which uses the verified
 * {@code World#execute(Runnable)} API. Do not touch entity components directly
 * from a scheduler callback.</p>
 */
public final class SchedulerService {

    private final MysticCore core;
    private ScheduledExecutorService executor;

    public SchedulerService(MysticCore core) {
        this.core = core;
    }

    public void start() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "MysticEssentials-Scheduler-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        executor = Executors.newScheduledThreadPool(2, factory);
    }

    /** Runs {@code task} once after {@code delay}. */
    public ScheduledFuture<?> runLater(Runnable task, long delay, TimeUnit unit) {
        return executor.schedule(wrap(task), delay, unit);
    }

    /** Runs {@code task} repeatedly at a fixed interval, starting after {@code initialDelay}. */
    public ScheduledFuture<?> runRepeating(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return executor.scheduleAtFixedRate(wrap(task), initialDelay, period, unit);
    }

    private Runnable wrap(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                core.log(Level.SEVERE, "Scheduled task threw: " + t);
            }
        };
    }

    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
