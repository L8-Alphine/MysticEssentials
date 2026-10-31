package org.hyzionstudios.mysticessentials.modules.afk;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/**
 * Dependency-free checks that an AFK zone means one thing everywhere: the
 * blocks the two corners were set in, inclusive -- the same box /afkzone pos1
 * and pos2 draw while you mark it. Containment, the landing sampler and the
 * size message used to read the stored foot positions three different ways, so
 * the edge blocks the admin stood on were only partly inside the zone.
 */
public final class AfkZoneGeometryTest {

    private static final String WORLD = "default";

    public static void main(String[] args) {
        edgeBlocksYouStoodOnAreInside();
        cornerOrderDoesNotMatter();
        aSingleBlockZoneIsOneByOne();
        anotherWorldIsNeverInside();
        heightIsNotPartOfTheZone();
        samplesStayInsideAndReachBothEdges();
        System.out.println("AfkZoneGeometryTest: all checks passed.");
    }

    /** The case that read wrong before: foot positions part-way into their blocks. */
    private static void edgeBlocksYouStoodOnAreInside() {
        AfkConfig.Zone zone = zone(100.7, 100.2, 110.3, 105.9);
        require(zone.contains(at(100.1, 103)), "x=100.1 is in block 100, where corner A was set");
        require(zone.contains(at(110.9, 103)), "x=110.9 is in block 110, where corner B was set");
        require(zone.contains(at(105, 100.0)), "z=100.0 is in block 100, where corner A was set");
        require(zone.contains(at(105, 105.99)), "z=105.99 is in block 105, where corner B was set");
        require(!zone.contains(at(111.0, 103)), "x=111 is past the last block");
        require(!zone.contains(at(99.99, 103)), "x=99.99 is before the first block");
        require(!zone.contains(at(105, 106.0)), "z=106 is past the last block");
        requireEqual("11x6", AfkModule.formatSize(zone), "blocks 100..110 by 100..105");
    }

    private static void cornerOrderDoesNotMatter() {
        AfkConfig.Zone forward = zone(100.7, 100.2, 110.3, 105.9);
        AfkConfig.Zone reversed = zone(110.3, 105.9, 100.7, 100.2);
        requireEqual(AfkModule.formatSize(forward), AfkModule.formatSize(reversed), "size by corner order");
        for (double x = 99; x <= 112; x += 0.25) {
            require(forward.contains(at(x, 103)) == reversed.contains(at(x, 103)),
                    "containment at x=" + x + " depends on corner order");
        }
    }

    /**
     * Negative coordinates on purpose: z=-3.4 and z=-3.1 are both in block -4,
     * which spans -4.0 up to (not including) -3.0. Truncating instead of flooring
     * would put them in block -3 -- the classic off-by-one west and north of 0.
     */
    private static void aSingleBlockZoneIsOneByOne() {
        AfkConfig.Zone zone = zone(5.2, -3.4, 5.9, -3.1);
        requireEqual("1x1", AfkModule.formatSize(zone), "both corners in one block");
        require(zone.contains(at(5.0, -4.0)), "the block's near edge");
        require(zone.contains(at(5.99, -3.01)), "the block's far edge");
        require(!zone.contains(at(5.5, -3.0)), "z=-3.0 is block -3, the next one north");
        require(!zone.contains(at(6.0, -3.5)), "x=6 is the next block east");
    }

    private static void anotherWorldIsNeverInside() {
        AfkConfig.Zone zone = zone(0, 0, 10, 10);
        require(!zone.contains(new MysticLocation("nether", 5, 64, 5, 0f, 0f)), "a different world");
    }

    /** Documented behaviour, unchanged: corner Y only records where the admin stood. */
    private static void heightIsNotPartOfTheZone() {
        AfkConfig.Zone zone = zone(0, 0, 10, 10);
        require(zone.contains(new MysticLocation(WORLD, 5, -40, 5, 0f, 0f)), "far below the corners");
        require(zone.contains(new MysticLocation(WORLD, 5, 300, 5, 0f, 0f)), "far above the corners");
    }

    /** Every landing column the sampler picks must be inside, and both edges must be reachable. */
    private static void samplesStayInsideAndReachBothEdges() {
        AfkConfig.Zone zone = zone(100.7, 100.2, 110.3, 105.9);
        boolean sawMinX = false;
        boolean sawMaxX = false;
        for (int i = 0; i < 5000; i++) {
            int x = AfkModule.randomBlock(zone.cornerA.getX(), zone.cornerB.getX());
            int z = AfkModule.randomBlock(zone.cornerA.getZ(), zone.cornerB.getZ());
            require(zone.contains(at(x + 0.5, z + 0.5)), "sampled column " + x + "," + z + " is outside");
            sawMinX |= x == 100;
            sawMaxX |= x == 110;
        }
        require(sawMinX && sawMaxX, "the sampler never reached an edge block (min=" + sawMinX + ", max=" + sawMaxX + ")");
    }

    // ----- Helpers -------------------------------------------------------------------

    private static AfkConfig.Zone zone(double ax, double az, double bx, double bz) {
        return new AfkConfig.Zone("test", new MysticLocation(WORLD, ax, 64, az, 0f, 0f),
                new MysticLocation(WORLD, bx, 70, bz, 0f, 0f));
    }

    private static MysticLocation at(double x, double z) {
        return new MysticLocation(WORLD, x, 64, z, 0f, 0f);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void requireEqual(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but got " + actual);
        }
    }
}
