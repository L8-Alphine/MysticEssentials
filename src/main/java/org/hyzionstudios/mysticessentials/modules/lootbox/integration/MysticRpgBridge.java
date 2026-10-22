package org.hyzionstudios.mysticessentials.modules.lootbox.integration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.modules.lootbox.api.ConditionProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;

/**
 * MysticRPG (P3-WP7/WP9), bound by reflection to
 * {@code org.hyzionstudios.mysticrpg.api.MysticRPG}:
 *
 * <ul>
 *   <li>Condition {@code RPG_LEVEL} ({@code min}, {@code max}, either optional)
 *       against the cached profile's level.</li>
 *   <li>Condition {@code RPG_CLASS} ({@code class}: {@code mysticrpg:warrior}
 *       or {@code warrior}) against the player's current class.</li>
 *   <li>Reward provider {@code mysticrpg} (type {@code RPG}, {@code "xp": "250"}):
 *       experience through {@code progression().grantExperience} with a
 *       {@code mysticessentials:lootbox} modifier source. MysticRPG has no
 *       idempotency key, so it is {@code AT_MOST_ONCE}: an interrupted grant goes
 *       to staff review.</li>
 * </ul>
 *
 * <p><b>Pending:</b> MysticRPG does not build against Hytale 0.7.x yet, so no
 * server can load it today and this bridge registers nothing. It binds to the
 * API as it is in the MysticRPG repo; check it once MysticRPG ships for 0.7.</p>
 */
final class MysticRpgBridge {

    static final String LEVEL = "RPG_LEVEL";
    static final String CLASS = "RPG_CLASS";
    static final String PROVIDER = "mysticrpg";
    private static final long MAX_XP = 1_000_000_000L;

    private record Hooks(Method api, Method players, Method cached, Method level, Method classes, Method current,
            Method classKey, Method progression, Method grantExperience, Method keyOf, Method sourceOf,
            Method applied) {
    }

    private final Hooks hooks;

    private MysticRpgBridge(Hooks hooks) {
        this.hooks = hooks;
    }

    static MysticRpgBridge detect() {
        try {
            Class<?> entry = Reflect.require("org.hyzionstudios.mysticrpg.api.MysticRPG");
            Class<?> api = Reflect.require("org.hyzionstudios.mysticrpg.api.MysticRPGApi");
            Class<?> profile = Reflect.require("org.hyzionstudios.mysticrpg.api.profile.RPGProfile");
            Class<?> classService = Reflect.require("org.hyzionstudios.mysticrpg.api.classes.ClassService");
            Class<?> rpgClass = Reflect.require("org.hyzionstudios.mysticrpg.api.classes.RPGClass");
            Class<?> key = Reflect.require("org.hyzionstudios.mysticrpg.api.key.NamespacedKey");
            Class<?> source = Reflect.require("org.hyzionstudios.mysticrpg.api.modifier.ModifierSource");
            Class<?> grant = Reflect.require("org.hyzionstudios.mysticrpg.api.progression.ExperienceGrant");
            Method players = Reflect.method(api, "players");
            Method progression = Reflect.method(api, "progression");
            Hooks hooks = new Hooks(Reflect.method(entry, "api"), players,
                    Reflect.method(players.getReturnType(), "cached", UUID.class), Reflect.method(profile, "level"),
                    Reflect.method(api, "classes"), Reflect.method(classService, "current", profile),
                    Reflect.method(rpgClass, "key"), progression,
                    Reflect.method(progression.getReturnType(), "grantExperience", UUID.class, long.class, source),
                    Reflect.method(key, "of", String.class, String.class),
                    Reflect.method(source, "of", key, String.class), Reflect.method(grant, "applied"));
            return new MysticRpgBridge(hooks);
        } catch (Throwable t) {
            return null;
        }
    }

    ConditionProvider level() {
        return new Level();
    }

    ConditionProvider rpgClass() {
        return new RpgClass();
    }

    RewardProvider experience() {
        return new Experience();
    }

    private Object api() {
        try {
            Object maybe = Reflect.call(hooks.api(), null);
            return maybe instanceof Optional<?> optional ? optional.orElse(null) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The player's cached profile, or {@code null}. */
    private Object profile(Object api, UUID player) {
        Object maybe = Reflect.call(hooks.cached(), Reflect.call(hooks.players(), api), player);
        return maybe instanceof Optional<?> optional ? optional.orElse(null) : null;
    }

    private static Integer integer(Map<String, String> params, String name) {
        String raw = params.get(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private final class Level implements ConditionProvider {

        @Override
        public String type() {
            return LEVEL;
        }

        @Override
        public List<String> validate(Map<String, String> params) {
            List<String> problems = new ArrayList<>();
            Integer min = integer(params, "min");
            Integer max = integer(params, "max");
            if (min == null && max == null) {
                problems.add("RPG_LEVEL needs min, max or both");
            }
            if ((min != null && min < 0) || (max != null && max < 0)) {
                problems.add("min and max must be whole numbers of at least 0");
            }
            if (min != null && max != null && min >= 0 && max >= 0 && max < min) {
                problems.add("max must not be below min");
            }
            return problems;
        }

        @Override
        public String describe(Map<String, String> params) {
            Integer min = integer(params, "min");
            Integer max = integer(params, "max");
            if (min != null && max != null) {
                return "RPG level " + min + " to " + max;
            }
            return min != null ? "RPG level " + min + " or higher" : "RPG level " + max + " or lower";
        }

        @Override
        public Decision test(UUID player, Map<String, String> params) {
            Object api = api();
            if (api == null) {
                return Decision.UNAVAILABLE;
            }
            try {
                Object profile = profile(api, player);
                if (profile == null) {
                    return Decision.UNAVAILABLE;
                }
                int level = ((Number) Reflect.call(hooks.level(), profile)).intValue();
                Integer min = integer(params, "min");
                Integer max = integer(params, "max");
                boolean ok = (min == null || level >= min) && (max == null || level <= max);
                return ok ? Decision.PASS : Decision.FAIL;
            } catch (RuntimeException e) {
                return Decision.UNAVAILABLE;
            }
        }
    }

    private final class RpgClass implements ConditionProvider {

        @Override
        public String type() {
            return CLASS;
        }

        @Override
        public List<String> validate(Map<String, String> params) {
            String raw = params.get("class");
            return raw != null && IntegrationRules.RPG_KEY.matcher(IntegrationRules.rpgKey(raw)).matches() ? List.of()
                    : List.of("class must be a MysticRPG class key such as mysticrpg:warrior");
        }

        @Override
        public String describe(Map<String, String> params) {
            return "RPG class " + IntegrationRules.rpgKey(params.get("class"));
        }

        @Override
        public Decision test(UUID player, Map<String, String> params) {
            Object api = api();
            if (api == null) {
                return Decision.UNAVAILABLE;
            }
            try {
                Object profile = profile(api, player);
                Object classes = Reflect.call(hooks.classes(), api);
                if (profile == null || !(classes instanceof Optional<?> service) || service.isEmpty()) {
                    return Decision.UNAVAILABLE;
                }
                Object current = Reflect.call(hooks.current(), service.get(), profile);
                if (current == null) {
                    return Decision.FAIL;
                }
                String key = String.valueOf(Reflect.call(hooks.classKey(), current));
                return IntegrationRules.rpgKey(params.get("class")).equals(key) ? Decision.PASS : Decision.FAIL;
            } catch (RuntimeException e) {
                return Decision.UNAVAILABLE;
            }
        }
    }

    private final class Experience implements RewardProvider {

        @Override
        public String id() {
            return PROVIDER;
        }

        @Override
        public String kind() {
            return "RPG";
        }

        @Override
        public Guarantee guarantee() {
            return Guarantee.AT_MOST_ONCE;
        }

        @Override
        public List<String> validate(Map<String, String> params) {
            long xp = xp(params);
            return xp > 0 && xp <= MAX_XP ? List.of() : List.of("xp must be a whole number from 1 to " + MAX_XP);
        }

        @Override
        public String describe(Map<String, String> params) {
            return "RPG experience: " + xp(params) + " XP";
        }

        @Override
        public CompletableFuture<Result> grant(String fulfillmentId, UUID player, Map<String, String> params) {
            Object api = api();
            if (api == null) {
                return CompletableFuture.completedFuture(Result.failed("MysticRPG is not running"));
            }
            long xp = xp(params);
            if (xp <= 0 || xp > MAX_XP) {
                return CompletableFuture.completedFuture(Result.failed("bad xp " + params.get("xp")));
            }
            try {
                Object namespace = Reflect.call(hooks.keyOf(), null, "mysticessentials", "lootbox");
                Object source = Reflect.call(hooks.sourceOf(), null, namespace, fulfillmentId);
                Object grant = Reflect.call(hooks.grantExperience(), Reflect.call(hooks.progression(), api), player,
                        xp, source);
                return CompletableFuture.completedFuture(Result.delivered("applied "
                        + Reflect.call(hooks.applied(), grant) + " of " + xp + " XP"));
            } catch (RuntimeException e) {
                return CompletableFuture.completedFuture(Result.unknown("grantExperience threw " + e));
            }
        }

        private long xp(Map<String, String> params) {
            try {
                String raw = params.get("xp");
                return raw == null ? 0 : Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
