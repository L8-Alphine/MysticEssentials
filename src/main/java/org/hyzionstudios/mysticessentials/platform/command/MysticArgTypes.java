package org.hyzionstudios.mysticessentials.platform.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * String argument types that feed the client's tab-completion popup.
 *
 * <p><b>Why these exist</b> (verified against Server 0.5.6): the per-argument
 * {@code Argument.suggest(SuggestionProvider)} hook never reaches the client.
 * Its only reader is {@code Argument.getSuggestions(...)}, which nothing in the
 * server calls. What the client actually does is send an
 * {@code ArgValuesRequest{argTypeId, partial}}, and {@code GamePacketHandler}
 * answers it with {@code CommandManager.getArgTypeById(argTypeId).suggest(...)}
 * — i.e. suggestions are a property of the <b>argument type</b>, not of the
 * argument. The type id travels to the client inside the command tree
 * ({@code CommandArgInfo.argTypeId}), and every {@code ArgumentType} instance
 * gets its own id, so a shared instance here means one id (and one client-side
 * cache entry) per kind of suggestion.</p>
 *
 * <p>Types are stateless and thread-safe; suggestion callbacks run on a Netty
 * IO thread, so they only read already-published state (the universe player and
 * world tables, module config) and never touch entity components.</p>
 */
public final class MysticArgTypes {

    // Translation keys the vanilla client already ships, so usage/help text for
    // these arguments renders exactly like the built-in equivalents.
    private static final String PLAYER_NAME_KEY = "server.commands.parsing.argtype.player.name";
    private static final String PLAYER_USAGE_KEY = "server.commands.parsing.argtype.player.usage";
    private static final String WORLD_NAME_KEY = "server.commands.parsing.argtype.world.name";
    private static final String WORLD_USAGE_KEY = "server.commands.parsing.argtype.world.usage";
    private static final String STRING_NAME_KEY = "server.commands.parsing.argtype.string.name";
    private static final String STRING_USAGE_KEY = "server.commands.parsing.argtype.string.usage";

    /** Bound once by the Core; only used to vanish-filter player suggestions. */
    private static volatile MysticCore core;

    private MysticArgTypes() {
    }

    /** Publishes the Core so suggestions can respect vanish state. */
    public static void bind(MysticCore instance) {
        core = instance;
    }

    /**
     * An online player's username, vanish-filtered for the requesting sender.
     * Shared by every command that takes a player name so the client caches one
     * list; use it in place of {@code ArgTypes.STRING} for player arguments.
     */
    public static final SingleArgumentType<String> PLAYER_NAME = new DynamicString(
            PLAYER_NAME_KEY, PLAYER_USAGE_KEY, MysticArgTypes::visiblePlayerNames);

    /** The name of a loaded world. */
    public static final SingleArgumentType<String> WORLD_NAME = new DynamicString(
            WORLD_NAME_KEY, WORLD_USAGE_KEY, sender -> worldNames());

    /**
     * A string argument whose suggestions are computed per request from
     * {@code values} (already-loaded state only — see the class note on
     * threading). Each call creates a new type with its own suggestion id, so
     * hold the result in a field rather than building one per suggestion.
     */
    public static SingleArgumentType<String> dynamic(Function<CommandSender, Collection<String>> values) {
        return new DynamicString(STRING_NAME_KEY, STRING_USAGE_KEY, values);
    }

    /** Online player usernames the sender is allowed to see. */
    public static List<String> visiblePlayerNames(CommandSender sender) {
        MysticCore instance = core;
        List<String> names = new ArrayList<>();
        if (instance != null) {
            for (PlayerRef player : instance.vanish().visiblePlayers(sender == null ? null : sender.getUuid())) {
                names.add(player.getUsername());
            }
            return names;
        }
        // Core not bound yet (very early boot): fall back to the raw player list.
        for (PlayerRef player : Universe.get().getPlayers()) {
            names.add(player.getUsername());
        }
        return names;
    }

    /** Names of every loaded world. */
    public static List<String> worldNames() {
        List<String> names = new ArrayList<>();
        for (World world : Universe.get().getWorlds().values()) {
            if (world != null && world.getName() != null && !world.getName().isBlank()) {
                names.add(world.getName());
            }
        }
        return names;
    }

    /** Parses as a plain string; suggests a prefix-filtered list supplied per request. */
    private static final class DynamicString extends SingleArgumentType<String> {

        private final Function<CommandSender, Collection<String>> values;

        DynamicString(String nameKey, String usageKey, Function<CommandSender, Collection<String>> values) {
            super(nameKey, usageKey);
            this.values = values;
        }

        @Override
        public String parse(String token, ParseResult result) {
            return ArgTypes.STRING.parse(token, result);
        }

        @Override
        public void suggest(CommandSender sender, String input, int index, SuggestionResult result) {
            String partial = partialToken(input);
            try {
                for (String value : values.apply(sender)) {
                    if (value != null && !value.isBlank()
                            && value.toLowerCase(Locale.ROOT).startsWith(partial)) {
                        result.suggest(value);
                    }
                }
            } catch (Throwable t) {
                // A suggestion failure must never break the sender's command input.
                MysticCore instance = core;
                if (instance != null) {
                    instance.log(java.util.logging.Level.WARNING, "Argument suggestion failed: " + t);
                }
            }
        }

        /**
         * The token being completed. The client sends just the partial word, but
         * the (unused in 0.5.6) per-argument path passes every token joined, so
         * take the last one either way.
         */
        private static String partialToken(String input) {
            if (input == null || input.isBlank()) {
                return "";
            }
            String trimmed = input.trim();
            int lastSpace = trimmed.lastIndexOf(' ');
            String token = lastSpace < 0 ? trimmed : trimmed.substring(lastSpace + 1);
            return token.toLowerCase(Locale.ROOT);
        }
    }
}
