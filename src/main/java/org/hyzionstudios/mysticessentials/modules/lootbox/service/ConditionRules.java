package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.modules.lootbox.api.ConditionProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;

/**
 * Eligibility conditions (P3-WP3). Four are built in and answered from what the
 * server already knows about the player: {@code PERMISSION}, {@code WORLD},
 * {@code REGION} and {@code DATE_WINDOW}. Every other type is a registered
 * {@link ConditionProvider}. A condition that can't be answered is
 * {@link ConditionProvider.Decision#UNAVAILABLE} and fails closed. Engine-free:
 * the caller collects the player's facts into a {@link Context}.
 */
public final class ConditionRules {

    public static final Set<String> BUILT_IN = IntegrationRegistry.BUILT_IN_CONDITIONS;
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_.\\-*]+");

    /**
     * What the conditions may read about a player. {@code world} is {@code null}
     * and {@code positionKnown} false when the player is between worlds.
     */
    public record Context(UUID player, Predicate<String> hasPermission, String world, double x, double y, double z,
            boolean positionKnown, long now) {
    }

    private ConditionRules() {
    }

    /** The condition's decision for this player, with {@code negate} applied (UNAVAILABLE stays UNAVAILABLE). */
    public static ConditionProvider.Decision test(LootBoxDraft.ConditionSpec condition, Context context,
            IntegrationRegistry providers) {
        ConditionProvider.Decision raw = raw(condition, context, providers);
        if (raw == ConditionProvider.Decision.UNAVAILABLE || !condition.negate) {
            return raw;
        }
        return raw == ConditionProvider.Decision.PASS ? ConditionProvider.Decision.FAIL
                : ConditionProvider.Decision.PASS;
    }

    private static ConditionProvider.Decision raw(LootBoxDraft.ConditionSpec condition, Context context,
            IntegrationRegistry providers) {
        Map<String, String> params = condition.params();
        try {
            switch (type(condition)) {
                case "PERMISSION" -> {
                    String node = params.get("node");
                    return node != null && context.hasPermission().test(node) ? pass() : fail();
                }
                case "WORLD" -> {
                    if (context.world() == null) {
                        return ConditionProvider.Decision.UNAVAILABLE;
                    }
                    for (String world : list(params.get("worlds"))) {
                        if (world.equalsIgnoreCase(context.world())) {
                            return pass();
                        }
                    }
                    return fail();
                }
                case "REGION" -> {
                    if (!context.positionKnown() || context.world() == null) {
                        return ConditionProvider.Decision.UNAVAILABLE;
                    }
                    String world = params.get("world");
                    if (world != null && !world.isBlank() && !world.equalsIgnoreCase(context.world())) {
                        return fail();
                    }
                    long[] min = point(params.get("min"));
                    long[] max = point(params.get("max"));
                    long bx = (long) Math.floor(context.x());
                    long by = (long) Math.floor(context.y());
                    long bz = (long) Math.floor(context.z());
                    return between(bx, min[0], max[0]) && between(by, min[1], max[1]) && between(bz, min[2], max[2])
                            ? pass() : fail();
                }
                case "DATE_WINDOW" -> {
                    String from = params.get("from");
                    String until = params.get("until");
                    if (from != null && !from.isBlank() && context.now() < Instant.parse(from.trim()).toEpochMilli()) {
                        return fail();
                    }
                    if (until != null && !until.isBlank()
                            && context.now() >= Instant.parse(until.trim()).toEpochMilli()) {
                        return fail();
                    }
                    return pass();
                }
                default -> {
                    ConditionProvider provider = providers.condition(type(condition));
                    if (provider == null) {
                        return ConditionProvider.Decision.UNAVAILABLE;
                    }
                    ConditionProvider.Decision decision = provider.test(context.player(), params);
                    return decision == null ? ConditionProvider.Decision.UNAVAILABLE : decision;
                }
            }
        } catch (RuntimeException e) {
            // A malformed parameter that slipped past publishing, or a provider that threw.
            return ConditionProvider.Decision.UNAVAILABLE;
        }
    }

    /** Publish-time problems of one condition; {@code where} prefixes each, e.g. {@code reward 'gem'}. */
    public static List<String> validate(LootBoxDraft.ConditionSpec condition, String where,
            IntegrationRegistry providers) {
        List<String> problems = new ArrayList<>();
        if (condition == null || condition.type == null || condition.type.isBlank()) {
            problems.add(where + ": a condition has no type");
            return problems;
        }
        String type = type(condition);
        Map<String, String> params = condition.params();
        String label = where + " condition " + type;
        switch (type) {
            case "PERMISSION" -> {
                String node = params.get("node");
                if (node == null || !NODE.matcher(node).matches()) {
                    problems.add(label + ": params.node is missing or has invalid characters");
                }
            }
            case "WORLD" -> {
                if (list(params.get("worlds")).isEmpty()) {
                    problems.add(label + ": params.worlds lists no world");
                }
            }
            case "REGION" -> {
                try {
                    long[] min = point(params.get("min"));
                    long[] max = point(params.get("max"));
                    for (int i = 0; i < 3; i++) {
                        if (min[i] > max[i]) {
                            problems.add(label + ": params.min must not exceed params.max");
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    problems.add(label + ": params.min and params.max must be \"x,y,z\" block coordinates");
                }
            }
            case "DATE_WINDOW" -> {
                String from = params.get("from");
                String until = params.get("until");
                if ((from == null || from.isBlank()) && (until == null || until.isBlank())) {
                    problems.add(label + ": set params.from, params.until or both");
                }
                Long start = instant(from, label + ": params.from", problems);
                Long end = instant(until, label + ": params.until", problems);
                if (start != null && end != null && start >= end) {
                    problems.add(label + ": params.from must be before params.until");
                }
            }
            default -> {
                ConditionProvider provider = providers.condition(type);
                if (provider == null) {
                    problems.add(label + ": no plugin provides this condition (is its integration installed?)");
                } else {
                    try {
                        for (String problem : provider.validate(params)) {
                            problems.add(label + ": " + problem);
                        }
                    } catch (RuntimeException e) {
                        problems.add(label + ": the provider failed to check it (" + e.getMessage() + ")");
                    }
                }
            }
        }
        return problems;
    }

    /** The rule as players read it: the operator's label, or one generated from the type. */
    public static String describe(LootBoxDraft.ConditionSpec condition, IntegrationRegistry providers) {
        if (condition.label != null && !condition.label.isBlank()) {
            return condition.label;
        }
        Map<String, String> params = condition.params();
        String text = switch (type(condition)) {
            case "PERMISSION" -> "the permission " + params.get("node");
            case "WORLD" -> "being in " + String.join(" or ", list(params.get("worlds")));
            case "REGION" -> "being inside an area" + (params.get("world") == null ? "" : " of "
                    + params.get("world"));
            case "DATE_WINDOW" -> "the dates " + (params.get("from") == null ? "…" : params.get("from")) + " to "
                    + (params.get("until") == null ? "…" : params.get("until"));
            default -> {
                ConditionProvider provider = providers.condition(type(condition));
                String described = null;
                try {
                    described = provider == null ? null : provider.describe(params);
                } catch (RuntimeException ignored) {
                    // Falls back to the type name.
                }
                yield described == null || described.isBlank() ? type(condition) : described;
            }
        };
        return condition.negate ? "not " + text : text;
    }

    public static String type(LootBoxDraft.ConditionSpec condition) {
        return condition.type == null ? "" : condition.type.trim().toUpperCase(Locale.ROOT);
    }

    private static ConditionProvider.Decision pass() {
        return ConditionProvider.Decision.PASS;
    }

    private static ConditionProvider.Decision fail() {
        return ConditionProvider.Decision.FAIL;
    }

    private static boolean between(long value, long min, long max) {
        return value >= min && value <= max;
    }

    static List<String> list(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static long[] point(String text) {
        String[] parts = text.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("not x,y,z: " + text);
        }
        return new long[] {Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim()),
                Long.parseLong(parts[2].trim())};
    }

    private static Long instant(String text, String label, List<String> problems) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim()).toEpochMilli();
        } catch (DateTimeParseException e) {
            problems.add(label + " must be an ISO-8601 instant such as 2026-12-01T00:00:00Z");
            return null;
        }
    }
}
