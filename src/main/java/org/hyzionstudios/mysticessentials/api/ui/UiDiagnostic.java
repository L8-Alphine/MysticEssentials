package org.hyzionstudios.mysticessentials.api.ui;

/** One compiler or validator finding. A line of zero means unavailable. */
public record UiDiagnostic(Severity severity, String source, int line, String message) {
    public enum Severity { INFO, WARNING, ERROR }

    public UiDiagnostic {
        source = source == null ? "<memory>" : source;
        line = Math.max(0, line);
        message = message == null ? "" : message;
    }
}
