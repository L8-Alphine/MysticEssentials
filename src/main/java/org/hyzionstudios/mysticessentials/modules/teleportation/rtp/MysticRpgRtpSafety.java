package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Reflection-backed soft integration with MysticRPG's API v1.
 *
 * <p>No MysticRPG classes appear in this mod's constant pool, so servers can
 * still run MysticEssentials without MysticRPG installed. When it is present,
 * the bridge uses the same world-name normalization and effective content-level
 * calculation that MysticRPG uses for mob nameplates and stats.</p>
 */
final class MysticRpgRtpSafety {

    static final String ABOVE_RANGE = "mysticrpg_level_above_range";
    static final String BELOW_RANGE = "mysticrpg_level_below_range";
    static final String PROFILE_UNAVAILABLE = "mysticrpg_profile_unavailable";
    static final String INTEGRATION_ERROR = "mysticrpg_safety_error";

    private static final String ENTRY_POINT = "org.hyzionstudios.mysticrpg.api.MysticRPG";
    private static final String API_TYPE = "org.hyzionstudios.mysticrpg.api.MysticRPGApi";
    private static final String PROFILE_SERVICE =
            "org.hyzionstudios.mysticrpg.api.profile.ProfileService";
    private static final String PROFILE_TYPE = "org.hyzionstudios.mysticrpg.api.profile.RPGProfile";
    private static final String WORLD_SERVICE = "org.hyzionstudios.mysticrpg.api.world.WorldService";
    private static final String WORLD_POSITION = "org.hyzionstudios.mysticrpg.api.world.WorldPosition";
    private static final String REGION_TYPE = "org.hyzionstudios.mysticrpg.api.world.Region";
    private static final String KEY_TYPE = "org.hyzionstudios.mysticrpg.api.key.NamespacedKey";

    private final MysticCore core;
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile Access access;
    private volatile boolean absent;
    private volatile boolean incompatible;

    MysticRpgRtpSafety(MysticCore core) {
        this.core = core;
    }

    /** @return a machine-readable rejection reason, or {@code null} when accepted. */
    String reject(UUID playerId, MysticLocation candidate,
            RandomTeleportConfig.MysticRpgSafety settings) {
        if (settings == null || !settings.enabled || playerId == null || candidate == null) {
            return null;
        }

        Access api = resolve();
        if (api == null) {
            return incompatible && settings.rejectOnIntegrationError ? INTEGRATION_ERROR : null;
        }

        try {
            Optional<?> installed = optional(api.api().invoke(null));
            if (installed.isEmpty()) {
                // The optional dependency may be absent or still starting. It is
                // not an integration failure until MysticRPG publishes its API.
                return null;
            }

            Object mysticRpg = installed.get();
            Optional<?> world = optional(api.world().invoke(mysticRpg));
            if (world.isEmpty()) {
                // MysticRPG explicitly makes the World module optional. With no
                // WorldService there are no RPG content levels to validate.
                return null;
            }

            Object profiles = api.players().invoke(mysticRpg);
            Optional<?> profile = optional(api.cachedProfile().invoke(profiles, playerId));
            if (profile.isEmpty()) {
                return settings.rejectWhenProfileUnavailable ? PROFILE_UNAVAILABLE : null;
            }

            int playerLevel = number(api.profileLevel().invoke(profile.get()));
            Object key = api.worldKey().invoke(null, normalizeWorld(candidate.getWorld()));
            Object position = api.worldPosition().invoke(null, key,
                    candidate.getX(), candidate.getY(), candidate.getZ());

            Object worldService = world.get();
            if (settings.allowSafeRegions) {
                Optional<?> region = optional(api.regionAt().invoke(worldService, position));
                if (region.isPresent() && Boolean.TRUE.equals(api.regionSafe().invoke(region.get()))) {
                    return null;
                }
            }

            int contentLevel = number(api.levelAt().invoke(worldService, position, playerLevel));
            return rejectionReason(playerLevel, contentLevel,
                    settings.minimumLevelOffset, settings.maximumLevelOffset);
        } catch (Throwable error) {
            warn(error);
            return settings.rejectOnIntegrationError ? INTEGRATION_ERROR : null;
        }
    }

    static String rejectionReason(int playerLevel, int contentLevel,
            int minimumOffset, int maximumOffset) {
        int lowOffset = Math.min(minimumOffset, maximumOffset);
        int highOffset = Math.max(minimumOffset, maximumOffset);
        long minimum = Math.max(1L, (long) playerLevel + lowOffset);
        long maximum = Math.max(minimum, (long) playerLevel + highOffset);
        if (contentLevel < minimum) {
            return BELOW_RANGE;
        }
        if (contentLevel > maximum) {
            return ABOVE_RANGE;
        }
        return null;
    }

    /** Mirrors MysticRPG's public WorldKeys mapping without linking against it. */
    static String normalizeWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return "default";
        }
        String lowered = worldName.trim().toLowerCase(Locale.ROOT);
        StringBuilder path = new StringBuilder(Math.min(lowered.length(), 64));
        for (int index = 0; index < lowered.length() && path.length() < 64; index++) {
            char character = lowered.charAt(index);
            boolean legal = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_' || character == '.' || character == '/'
                    || character == '-';
            path.append(legal ? character : '_');
        }
        return path.isEmpty() ? "default" : path.toString();
    }

    private Access resolve() {
        Access resolved = access;
        if (resolved != null || absent || incompatible) {
            return resolved;
        }
        synchronized (this) {
            if (access != null || absent || incompatible) {
                return access;
            }
            try {
                ClassLoader loader = MysticRpgRtpSafety.class.getClassLoader();
                Class<?> entry = Class.forName(ENTRY_POINT, false, loader);
                Class<?> apiType = Class.forName(API_TYPE, false, loader);
                Class<?> profileService = Class.forName(PROFILE_SERVICE, false, loader);
                Class<?> profileType = Class.forName(PROFILE_TYPE, false, loader);
                Class<?> worldService = Class.forName(WORLD_SERVICE, false, loader);
                Class<?> position = Class.forName(WORLD_POSITION, false, loader);
                Class<?> region = Class.forName(REGION_TYPE, false, loader);
                Class<?> key = Class.forName(KEY_TYPE, false, loader);
                access = new Access(
                        entry.getMethod("api"),
                        apiType.getMethod("players"),
                        profileService.getMethod("cached", UUID.class),
                        profileType.getMethod("level"),
                        apiType.getMethod("world"),
                        key.getMethod("mystic", String.class),
                        position.getMethod("of", key, double.class, double.class, double.class),
                        worldService.getMethod("regionAt", position),
                        region.getMethod("safe"),
                        worldService.getMethod("levelAt", position, int.class));
                return access;
            } catch (ClassNotFoundException missing) {
                absent = true;
                return null;
            } catch (Throwable error) {
                incompatible = true;
                warn(error);
                return null;
            }
        }
    }

    private void warn(Throwable error) {
        if (warned.compareAndSet(false, true)) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            core.log(Level.WARNING, "MysticRPG RTP safety could not query the installed API; "
                    + "RTP will honor mysticRpgSafety.rejectOnIntegrationError: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static Optional<?> optional(Object value) {
        return value instanceof Optional<?> result ? result : Optional.empty();
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalStateException("MysticRPG returned a non-numeric level");
    }

    private record Access(
            Method api,
            Method players,
            Method cachedProfile,
            Method profileLevel,
            Method world,
            Method worldKey,
            Method worldPosition,
            Method regionAt,
            Method regionSafe,
            Method levelAt) {
    }
}
