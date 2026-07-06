package org.hyzionstudios.mysticessentials.core.placeholder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.api.service.PlaceholderService;
import org.hyzionstudios.mysticessentials.core.MysticCore;

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

    private final MysticCore core;
    private final Map<String, BiFunction<UUID, String, String>> resolvers = new ConcurrentHashMap<>();
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
            try {
                new MysticExpansion(core, this).register();
                core.log(Level.INFO, "Registered PlaceholderAPI expansion '%mystic_...%'");
            } catch (Throwable t) {
                core.log(Level.WARNING, "Failed to register Mystic PlaceholderAPI expansion: " + t);
            }
        }
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
        return result;
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
