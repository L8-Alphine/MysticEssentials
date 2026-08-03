package org.hyzionstudios.mysticessentials.core.placeholder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.api.service.PlaceholderService;
import org.hyzionstudios.mysticessentials.api.service.PlaytimeService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Durations;

import at.helpch.placeholderapi.PlaceholderAPI;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * {@link PlaceholderService} implementation.
 *
 * <p>Internal {@code {name}} placeholders are fully supported through a registry
 * any module can extend. When PlaceholderAPI-Hytale is installed, external
 * {@code %expansion_id%} placeholders are resolved through it
 * ({@code PlaceholderAPI.setPlaceholders}), and Mystic's own placeholders are
 * exposed to other plugins under the {@code %mystic_<name>%} identifier via
 * {@link MysticExpansion}.</p>
 */
public final class PlaceholderServiceImpl implements PlaceholderService {

    private static final Pattern INTERNAL = Pattern.compile("\\{([a-zA-Z0-9_]+)}");
    private static final Pattern EXTERNAL = Pattern.compile("%([a-zA-Z0-9_]+)%");

    /** How many times expansion registration is attempted before giving up. */
    private static final int EXPANSION_REGISTER_ATTEMPTS = 5;
    /** Base delay between attempts; multiplied by the attempt number (5s, 10s, 15s…). */
    private static final long EXPANSION_RETRY_DELAY_SECONDS = 5;
    /** Identifiers Mystic's placeholders answer to: {@code %mystic_x%} / {@code %mysticessentials_x%}. */
    private static final List<String> EXPANSION_IDENTIFIERS = List.of("mystic", "mysticessentials");

    private final MysticCore core;
    private final Map<String, BiFunction<UUID, String, String>> resolvers = new ConcurrentHashMap<>();
    /** Live expansion instances; only populated when PlaceholderAPI is present. */
    private final List<MysticExpansion> expansions = new java.util.ArrayList<>();
    private boolean placeholderApiAvailable;

    public PlaceholderServiceImpl(MysticCore core) {
        this.core = core;
    }

    public void init(boolean enabledInConfig) {
        placeholderApiAvailable = enabledInConfig && isClassPresent("at.helpch.placeholderapi.PlaceholderAPI");
        core.log(Level.INFO, "Placeholder integration: PlaceholderAPI "
                + (placeholderApiAvailable ? "connected" : "not present (internal placeholders only)"));
        registerBuiltins();
        if (placeholderApiAvailable) {
            registerExpansions(1);
        }
    }

    /**
     * Publishes Mystic's placeholders to PlaceholderAPI under both
     * {@code %mystic_<name>%} and {@code %mysticessentials_<name>%}.
     *
     * <p>Registration can legitimately fail on the first attempt: mod load order
     * is not guaranteed, so PlaceholderAPI's expansion manager may not be up yet
     * even though its classes are on the classpath. Rather than silently
     * leaving every {@code %mystic_…%} unresolved for the rest of the session,
     * failed attempts are retried on a backoff and the outcome is logged
     * honestly.</p>
     */
    private void registerExpansions(int attempt) {
        if (expansions.isEmpty()) {
            for (String identifier : EXPANSION_IDENTIFIERS) {
                expansions.add(new MysticExpansion(core, this, identifier));
            }
        }
        boolean allRegistered = true;
        for (MysticExpansion expansion : expansions) {
            if (expansion.isRegistered()) {
                continue;
            }
            try {
                allRegistered &= expansion.register();
            } catch (Throwable t) {
                allRegistered = false;
                if (attempt >= EXPANSION_REGISTER_ATTEMPTS) {
                    core.log(Level.WARNING, "Failed to register PlaceholderAPI expansion '"
                            + expansion.getIdentifier() + "': " + t);
                }
            }
        }
        if (allRegistered) {
            core.log(Level.INFO, "Registered PlaceholderAPI expansions "
                    + "'%mystic_...%' and '%mysticessentials_...%'");
            return;
        }
        if (attempt >= EXPANSION_REGISTER_ATTEMPTS) {
            core.log(Level.WARNING, "Could not register the Mystic PlaceholderAPI expansion after "
                    + attempt + " attempts — other mods will not see %mystic_...% placeholders. "
                    + "Mystic's own {name} placeholders are unaffected.");
            return;
        }
        core.scheduler().runLater(() -> registerExpansions(attempt + 1),
                (long) attempt * EXPANSION_RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /** Every registered placeholder name, sorted — the expansion's advertised list. */
    public List<String> registeredNames() {
        return resolvers.keySet().stream().sorted().toList();
    }

    private void registerBuiltins() {
        register("server_name", (uuid, arg) -> "Mystic");
        register("player_name", (uuid, arg) -> uuid == null ? ""
                : core.platform().findPlayer(uuid).map(PlayerRef::getUsername).orElse(""));
        register("luckperms_prefix", (uuid, arg) -> uuid == null ? ""
                : core.getPermissionService().prefix(uuid));
        register("luckperms_suffix", (uuid, arg) -> uuid == null ? ""
                : core.getPermissionService().suffix(uuid));
        register("group", (uuid, arg) -> uuid == null ? ""
                : nullToEmpty(core.getPermissionService().primaryGroup(uuid)));
        registerPlaytime();
    }

    /**
     * Playtime placeholders. Each of total/active/idle is exposed formatted
     * ({@code 2d 3h 15m}), in raw seconds, and in whole hours, plus
     * {@code {playtime}} as a short alias for the formatted total.
     */
    private void registerPlaytime() {
        registerPlaytimeFamily("playtime_total", PlaytimeService::totalPlaytimeSeconds);
        registerPlaytimeFamily("playtime_active", PlaytimeService::activePlaytimeSeconds);
        registerPlaytimeFamily("playtime_idle", PlaytimeService::idlePlaytimeSeconds);
        register("playtime", (uuid, arg) -> Durations.format(playtime(uuid,
                PlaytimeService::totalPlaytimeSeconds)));
        register("playtime_session", (uuid, arg) -> Durations.format(playtime(uuid,
                PlaytimeService::sessionSeconds)));
    }

    private void registerPlaytimeFamily(String name, ToSecondsFunction seconds) {
        register(name, (uuid, arg) -> Durations.format(playtime(uuid, seconds)));
        register(name + "_seconds", (uuid, arg) -> Long.toString(playtime(uuid, seconds)));
        register(name + "_hours", (uuid, arg) -> Durations.hours(playtime(uuid, seconds)));
    }

    private long playtime(UUID player, ToSecondsFunction seconds) {
        PlaytimeService playtime = core.getPlaytimeService();
        return player == null || playtime == null ? 0 : seconds.apply(playtime, player);
    }

    /** A single playtime counter on the {@link PlaytimeService}. */
    @FunctionalInterface
    private interface ToSecondsFunction {
        long apply(PlaytimeService service, UUID player);
    }

    @Override
    public boolean isPlaceholderApiAvailable() {
        return placeholderApiAvailable;
    }

    @Override
    public void register(String name, BiFunction<UUID, String, String> resolver) {
        resolvers.put(name.toLowerCase(), resolver);
    }

    @Override
    public String resolve(UUID player, String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = resolveInternal(player, input);
        if (placeholderApiAvailable) {
            result = resolveExternal(player, result);
        }
        return resolveRemaining(player, result);
    }

    /**
     * Fills {@code %name%} tokens that nothing else resolved from Mystic's own
     * registry, accepting the bare name as well as the {@code mystic_} and
     * {@code mysticessentials_} prefixes.
     *
     * <p>Without this, {@code %player_name%} stays literal unless some
     * PlaceholderAPI expansion happens to publish that exact name — surprising
     * for an author writing a GUI or chat format, who has no reason to care
     * which side of the integration a value comes from. It runs last and only
     * on tokens PlaceholderAPI left untouched, so it can never override another
     * provider, and it works with PlaceholderAPI absent entirely.</p>
     */
    private String resolveRemaining(UUID player, String input) {
        Matcher matcher = EXTERNAL.matcher(input);
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase();
            String token = name.startsWith("mystic_") ? name.substring("mystic_".length())
                    : name.startsWith("mysticessentials_")
                            ? name.substring("mysticessentials_".length()) : name;
            BiFunction<UUID, String, String> resolver = resolvers.get(token);
            if (resolver == null) {
                continue;
            }
            replaced = true;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(safe(resolver, player, token)));
        }
        if (!replaced) {
            return input;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves a single Mystic token (the part after {@code mystic_}) for the
     * {@code %mystic_<token>%} PlaceholderAPI expansion. Returns {@code null} when
     * the token is unknown so PlaceholderAPI can leave it untouched.
     */
    public String resolveMysticToken(UUID player, String token) {
        if (token == null) {
            return null;
        }
        BiFunction<UUID, String, String> resolver = resolvers.get(token.toLowerCase());
        return resolver == null ? null : safe(resolver, player, token);
    }

    private String resolveInternal(UUID player, String input) {
        Matcher matcher = INTERNAL.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase();
            BiFunction<UUID, String, String> resolver = resolvers.get(name);
            String replacement = resolver == null ? matcher.group(0) : safe(resolver, player, name);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String safe(BiFunction<UUID, String, String> resolver, UUID player, String name) {
        try {
            String value = resolver.apply(player, name);
            return value == null ? "" : value;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Placeholder resolver '" + name + "' threw: " + t);
            return "";
        }
    }

    private String resolveExternal(UUID player, String input) {
        if (player == null || !EXTERNAL.matcher(input).find()) {
            return input;
        }
        return core.platform().findPlayer(player).map(ref -> {
            try {
                return PlaceholderAPI.setPlaceholders(ref, input);
            } catch (Throwable t) {
                core.log(Level.WARNING, "PlaceholderAPI resolution failed: " + t);
                return input;
            }
        }).orElse(input);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, PlaceholderServiceImpl.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
