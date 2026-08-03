package org.hyzionstudios.mysticessentials.api.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Server-authoritative typed action registry, validator and rate limiter. */
public final class UiActionRouter {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_./-]+)?");

    public record Policy(String permission, Map<String, ValueType> payload,
            Duration cooldown, int maxPerSecond, boolean sessionRequired,
            Consumer<UiActionContext> audit) {
        public Policy {
            payload = Map.copyOf(payload == null ? Map.of() : payload);
            cooldown = cooldown == null || cooldown.isNegative() ? Duration.ZERO : cooldown;
            maxPerSecond = Math.max(0, maxPerSecond);
        }
        public static Policy safeDefault() { return new Policy(null, Map.of(), Duration.ZERO, 10, true, null); }
    }

    public enum ValueType { STRING, INTEGER, NUMBER, BOOLEAN, UUID }
    private record Registered(UiActionHandler handler, Policy policy) { }
    private record Key(UUID player, String action) { }
    private static final class Window { long second; int count; }

    private final Map<String, Registered> handlers = new ConcurrentHashMap<>();
    private final Map<Key, Instant> lastRuns = new ConcurrentHashMap<>();
    private final Map<Key, Window> windows = new ConcurrentHashMap<>();

    public void register(String id, UiActionHandler handler) { register(id, handler, Policy.safeDefault()); }

    public void register(String id, UiActionHandler handler, Policy policy) {
        validateId(id);
        Registered previous = handlers.putIfAbsent(id, new Registered(Objects.requireNonNull(handler),
                policy == null ? Policy.safeDefault() : policy));
        if (previous != null) throw new IllegalStateException("UI action already registered: " + id);
    }

    public void replace(String id, UiActionHandler handler, Policy policy) {
        validateId(id);
        handlers.put(id, new Registered(Objects.requireNonNull(handler), policy == null ? Policy.safeDefault() : policy));
    }

    public void unregister(String id) { if (id != null) handlers.remove(id); }
    public boolean registered(String id) { return id != null && handlers.containsKey(id); }

    public UiActionResult dispatch(String id, UiActionContext context) {
        Registered registered = handlers.get(id);
        if (registered == null) return UiActionResult.rejected("Unknown UI action: " + id);
        if (context == null) return UiActionResult.rejected("Missing action context");
        Policy policy = registered.policy;
        if (policy.sessionRequired() && context.session() == null) return reject(context, "This action requires an active UI session");
        if (policy.permission() != null && !policy.permission().isBlank()
                && !context.permissionCheck().test(policy.permission())) return reject(context, "Permission denied");
        String invalid = validatePayload(context.payload(), policy.payload());
        if (invalid != null) return reject(context, invalid);

        Key key = new Key(context.playerId(), id);
        Instant now = context.timestamp();
        Instant previous = lastRuns.get(key);
        if (previous != null && now.isBefore(previous.plus(policy.cooldown()))) return reject(context, "Action is on cooldown");
        if (!withinRate(key, now, policy.maxPerSecond())) return reject(context, "Action rate limit exceeded");
        lastRuns.put(key, now);
        if (context.session() != null) context.session().lastAction(id);
        if (policy.audit() != null) policy.audit().accept(context);
        try {
            UiActionResult result = registered.handler.execute(context);
            if (result == null) return reject(context, "Action handler returned no result");
            if (context.session() != null && !result.stateChanges().isEmpty()) {
                context.session().localState().putAll(result.stateChanges());
            }
            return result;
        } catch (IllegalArgumentException e) {
            return reject(context, e.getMessage());
        } catch (Exception e) {
            return UiActionResult.error("Action failed: " + e.getMessage());
        }
    }

    public void clearPlayer(UUID playerId) {
        lastRuns.keySet().removeIf(key -> key.player.equals(playerId));
        windows.keySet().removeIf(key -> key.player.equals(playerId));
    }

    private boolean withinRate(Key key, Instant now, int maximum) {
        if (maximum <= 0) return true;
        long second = now.getEpochSecond();
        Window window = windows.computeIfAbsent(key, ignored -> new Window());
        synchronized (window) {
            if (window.second != second) { window.second = second; window.count = 0; }
            return ++window.count <= maximum;
        }
    }

    private static String validatePayload(Map<String, Object> values, Map<String, ValueType> schema) {
        for (Map.Entry<String, ValueType> field : schema.entrySet()) {
            Object value = values.get(field.getKey());
            if (value == null) return "Missing action field '" + field.getKey() + "'";
            boolean valid = switch (field.getValue()) {
                case STRING -> value instanceof String;
                case INTEGER -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                        || value instanceof String text && text.matches("-?\\d+");
                case NUMBER -> value instanceof Number || value instanceof String text && text.matches("-?\\d+(\\.\\d+)?");
                case BOOLEAN -> value instanceof Boolean || value instanceof String text
                        && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"));
                case UUID -> value instanceof UUID || value instanceof String text && uuid(text);
            };
            if (!valid) return "Invalid action field '" + field.getKey() + "' (expected " + field.getValue() + ")";
        }
        if (values.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            return "Action payload contains a null key or value";
        }
        return null;
    }

    private static UiActionResult reject(UiActionContext context, String message) {
        if (context.session() != null) context.session().lastValidationError(message);
        return UiActionResult.rejected(message);
    }

    private static boolean uuid(String value) { try { UUID.fromString(value); return true; } catch (IllegalArgumentException ignored) { return false; } }
    private static void validateId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid UI action id: " + id);
    }
}
