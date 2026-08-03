package org.hyzionstudios.mysticessentials.api.ui;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** Validated, server-side context passed to a typed UI action. */
public record UiActionContext(UUID playerId, UiSession session, String sourceComponentId,
        String route, Map<String, Object> payload, Predicate<String> permissionCheck, Instant timestamp) {
    public UiActionContext {
        Objects.requireNonNull(playerId, "playerId");
        payload = Map.copyOf(payload == null ? Map.of() : payload);
        permissionCheck = permissionCheck == null ? ignored -> false : permissionCheck;
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public String requireString(String key) { return String.valueOf(require(key)); }
    public int requireInt(String key) {
        Object value = require(key);
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Action field '" + key + "' must be an integer"); }
    }
    public Object require(String key) {
        Object value = payload.get(key);
        if (value == null) throw new IllegalArgumentException("Missing action field '" + key + "'");
        return value;
    }
}
