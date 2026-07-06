package org.hyzionstudios.mysticessentials.api.model;

import java.util.Objects;

/**
 * Serializable, platform-neutral location: a world name plus position and
 * orientation. Kept free of Hytale types so it can be stored in JSON/SQL and
 * moved across servers via Redis. Conversion to/from the Hytale
 * {@code Location}/{@code Transform} lives in the platform layer
 * ({@code platform.Conversions}).
 */
public final class MysticLocation {

    private String world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    /** No-arg constructor for JSON deserialization. */
    public MysticLocation() {
    }

    public MysticLocation(String world, double x, double y, double z, float yaw, float pitch) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public String getWorld() {
        return world;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    public void setX(double x) {
        this.x = x;
    }

    public void setY(double y) {
        this.y = y;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MysticLocation other)) {
            return false;
        }
        return Double.compare(x, other.x) == 0
                && Double.compare(y, other.y) == 0
                && Double.compare(z, other.z) == 0
                && Float.compare(yaw, other.yaw) == 0
                && Float.compare(pitch, other.pitch) == 0
                && Objects.equals(world, other.world);
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, x, y, z, yaw, pitch);
    }

    @Override
    public String toString() {
        return "MysticLocation{" + world + " " + x + "," + y + "," + z + " (" + yaw + "," + pitch + ")}";
    }
}
