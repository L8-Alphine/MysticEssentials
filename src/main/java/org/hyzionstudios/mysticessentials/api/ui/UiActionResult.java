package org.hyzionstudios.mysticessentials.api.ui;

import java.util.Map;

/** Result returned by a registered action handler. */
public record UiActionResult(Status status, String message, Map<String, Object> stateChanges) {
    public enum Status { SUCCESS, REJECTED, ERROR }
    public UiActionResult { stateChanges = Map.copyOf(stateChanges == null ? Map.of() : stateChanges); }
    public static UiActionResult success() { return new UiActionResult(Status.SUCCESS, null, Map.of()); }
    public static UiActionResult rejected(String message) { return new UiActionResult(Status.REJECTED, message, Map.of()); }
    public static UiActionResult error(String message) { return new UiActionResult(Status.ERROR, message, Map.of()); }
}
