package org.hyzionstudios.mysticessentials.platform;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

import com.hypixel.hytale.math.vector.Location;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * Converts between the platform-neutral {@link MysticLocation} and the Hytale
 * {@code math.vector.Location}/{@code Transform} types. All coupling to those
 * Hytale math types lives here so the rest of the codebase stays portable.
 *
 * <p>{@code Rotation3f} stores components as {@code (pitch, yaw, roll)}.
 * Player look direction is stored on {@link PlayerRef#getHeadRotation()}, while
 * the transform rotation is the body orientation.</p>
 */
public final class Conversions {

    private Conversions() {
    }

    /** Builds a Hytale {@link Location} from a stored {@link MysticLocation}. */
    public static Location toHytale(MysticLocation loc) {
        return new Location(loc.getWorld(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getPitch(), loc.getYaw(), 0.0f);
    }

    /** Builds a Hytale {@link Transform} from a stored {@link MysticLocation}. */
    public static Transform toTransform(MysticLocation loc) {
        Rotation3f rotation = new Rotation3f();
        rotation.setPitch(loc.getPitch());
        rotation.setYaw(loc.getYaw());
        rotation.setRoll(0.0f);
        return new Transform(new org.joml.Vector3d(loc.getX(), loc.getY(), loc.getZ()), rotation);
    }

    /**
     * Captures a player's current position and orientation as a
     * {@link MysticLocation} from the player's synced {@link Transform} snapshot.
     */
    public static MysticLocation capture(PlayerRef player) {
        Transform transform = player.getTransform();
        org.joml.Vector3d pos = transform.getPosition();
        Rotation3f rot = player.getHeadRotation();
        if (rot == null) {
            rot = transform.getRotation();
        }
        String worldName = resolveWorldName(player.getWorldUuid());
        return new MysticLocation(worldName, pos.x, pos.y, pos.z, rot.yaw(), rot.pitch());
    }

    /** Resolves a world's display name from its UUID, falling back to the UUID string. */
    public static String resolveWorldName(UUID worldUuid) {
        if (worldUuid == null) {
            return null;
        }
        try {
            World world = Universe.get().getWorld(worldUuid);
            return world != null ? world.getName() : worldUuid.toString();
        } catch (Throwable t) {
            return worldUuid.toString();
        }
    }
}
