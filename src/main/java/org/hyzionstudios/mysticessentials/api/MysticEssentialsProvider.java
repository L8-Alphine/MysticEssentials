package org.hyzionstudios.mysticessentials.api;

/**
 * Static access point to the live {@link MysticEssentialsAPI} instance.
 *
 * <pre>{@code
 * MysticEssentialsAPI api = MysticEssentialsProvider.get();
 * api.getTeleportService().teleport(player, request);
 * }</pre>
 *
 * <p>The instance is installed by the Core during plugin start and cleared on
 * shutdown. Calling {@link #get()} before the mod has fully started, or after it
 * has shut down, throws {@link IllegalStateException}.</p>
 */
public final class MysticEssentialsProvider {

    private static volatile MysticEssentialsAPI instance;

    private MysticEssentialsProvider() {
    }

    /** @return the live API, never {@code null}. */
    public static MysticEssentialsAPI get() {
        MysticEssentialsAPI api = instance;
        if (api == null) {
            throw new IllegalStateException(
                    "MysticEssentials API is not available yet. "
                            + "Declare Mystic Essentials as a dependency and access it after it has started.");
        }
        return api;
    }

    /** @return {@code true} if the API is currently available. */
    public static boolean isAvailable() {
        return instance != null;
    }

    /** Internal: installs the live API instance. Called by the Core only. */
    public static void register(MysticEssentialsAPI api) {
        instance = api;
    }

    /** Internal: clears the API instance on shutdown. Called by the Core only. */
    public static void unregister() {
        instance = null;
    }
}
