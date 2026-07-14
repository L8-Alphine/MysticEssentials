package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.util.List;
import java.util.Random;

import org.hyzionstudios.mysticessentials.api.rtp.RtpProfile;

/**
 * Generates random candidate X/Z coordinates inside a profile's configured
 * boundary shape (spec §4, step 2). Pure math — no world access — so it is cheap
 * and thread-safe. Returns {@code null} when a shape cannot yield a point within
 * a bounded number of internal attempts (e.g. a degenerate polygon).
 */
final class RtpShapeSampler {

    /** Internal resample cap for rejection-based shapes (SQUARE/RECTANGLE/POLYGON with a keep-out). */
    private static final int MAX_REJECTION_TRIES = 24;

    private RtpShapeSampler() {
    }

    /** @return {@code [x, z]} world coordinates, or {@code null} if none could be produced. */
    static double[] sample(RtpProfile profile, Random rng) {
        double cx = profile.center == null ? 0 : profile.center.x;
        double cz = profile.center == null ? 0 : profile.center.z;
        int outer = Math.max(1, profile.maximumRadius - Math.max(0, profile.borderPadding));
        int inner = Math.max(0, Math.min(profile.minimumRadius, outer - 1));

        return switch (profile.shape) {
            case CIRCLE, RING -> sampleAnnulus(cx, cz, inner, outer, rng);
            case SQUARE, WORLD_BORDER -> sampleBox(cx, cz, outer, outer, inner, rng);
            case RECTANGLE -> {
                int halfW = profile.halfWidth > 0 ? profile.halfWidth : outer;
                int halfD = profile.halfDepth > 0 ? profile.halfDepth : outer;
                yield sampleBox(cx, cz, halfW, halfD, inner, rng);
            }
            case POLYGON -> samplePolygon(profile.polygon, rng);
        };
    }

    private static double[] sampleAnnulus(double cx, double cz, int inner, int outer, Random rng) {
        // Uniform-area sampling of the annulus between inner and outer radii.
        double r = Math.sqrt(rng.nextDouble() * ((double) outer * outer - (double) inner * inner)
                + (double) inner * inner);
        double angle = rng.nextDouble() * Math.PI * 2.0;
        return new double[] {cx + r * Math.cos(angle), cz + r * Math.sin(angle)};
    }

    private static double[] sampleBox(double cx, double cz, int halfW, int halfD, int inner, Random rng) {
        for (int i = 0; i < MAX_REJECTION_TRIES; i++) {
            double x = cx + (rng.nextDouble() * 2.0 - 1.0) * halfW;
            double z = cz + (rng.nextDouble() * 2.0 - 1.0) * halfD;
            if (inner <= 0 || Math.abs(x - cx) >= inner || Math.abs(z - cz) >= inner) {
                return new double[] {x, z};
            }
        }
        return null;
    }

    private static double[] samplePolygon(List<RtpProfile.Vertex> polygon, Random rng) {
        if (polygon == null || polygon.size() < 3) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (RtpProfile.Vertex v : polygon) {
            minX = Math.min(minX, v.x);
            maxX = Math.max(maxX, v.x);
            minZ = Math.min(minZ, v.z);
            maxZ = Math.max(maxZ, v.z);
        }
        for (int i = 0; i < MAX_REJECTION_TRIES; i++) {
            double x = minX + rng.nextDouble() * (maxX - minX);
            double z = minZ + rng.nextDouble() * (maxZ - minZ);
            if (contains(polygon, x, z)) {
                return new double[] {x, z};
            }
        }
        return null;
    }

    /** Standard ray-casting point-in-polygon test on the X/Z plane. */
    private static boolean contains(List<RtpProfile.Vertex> polygon, double x, double z) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).x;
            double zi = polygon.get(i).z;
            double xj = polygon.get(j).x;
            double zj = polygon.get(j).z;
            boolean intersects = (zi > z) != (zj > z)
                    && x < (xj - xi) * (z - zi) / (zj - zi) + xi;
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }
}
