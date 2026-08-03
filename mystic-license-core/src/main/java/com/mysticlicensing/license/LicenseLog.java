package com.mysticlicensing.license;

import java.util.Objects;

/**
 * The logging seam, deliberately two methods wide.
 *
 * <p>Every host has its own logger and none of them agree on an interface, so
 * the core takes this instead of a dependency. A mod adapts its own logger in
 * three lines; nothing else in the library knows how logging works.
 *
 * <p>There is no {@code error} level on purpose. Nothing this library reports
 * is an error for the server: the worst case is a licensed feature staying off,
 * which is a warning.
 */
public interface LicenseLog {

    void info(String message);

    void warn(String message);

    /** Routes to {@link System.Logger}, which is in the JDK. The default. */
    static LicenseLog system(String name) {
        System.Logger logger = System.getLogger(name);
        return new LicenseLog() {
            @Override
            public void info(String message) {
                logger.log(System.Logger.Level.INFO, message);
            }

            @Override
            public void warn(String message) {
                logger.log(System.Logger.Level.WARNING, message);
            }
        };
    }

    /** Discards everything. Useful in tests that assert on behaviour, not output. */
    static LicenseLog silent() {
        return new LicenseLog() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
            }
        };
    }

    /**
     * Wraps a log so a throwing or misbehaving host logger cannot propagate out
     * of the licensing code. Applied automatically by {@code LicenseGate}.
     */
    static LicenseLog guarded(LicenseLog delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new LicenseLog() {
            @Override
            public void info(String message) {
                try {
                    delegate.info(message);
                } catch (Throwable ignored) {
                    // A broken logger must not become a licensing failure.
                }
            }

            @Override
            public void warn(String message) {
                try {
                    delegate.warn(message);
                } catch (Throwable ignored) {
                    // As above.
                }
            }
        };
    }
}
