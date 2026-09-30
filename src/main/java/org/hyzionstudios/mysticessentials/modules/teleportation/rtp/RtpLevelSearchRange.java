package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.util.Random;

import org.hyzionstudios.mysticessentials.api.rtp.RtpProfile;

/**
 * Narrows a search's radius from MysticRPG level feedback. MysticRPG worlds
 * progress outward from the configured profile center: content that is too high
 * moves the search inward, while content that is too low moves it outward.
 */
final class RtpLevelSearchRange {

    private static final double MINIMUM_BAND_WIDTH = 1.0;

    private final RtpProfile profile;
    private double inner;
    private double outer;

    RtpLevelSearchRange(RtpProfile profile) {
        this.profile = profile;
        this.inner = RtpShapeSampler.effectiveInnerRadius(profile);
        this.outer = RtpShapeSampler.effectiveOuterRadius(profile);
    }

    double[] sample(Random random) {
        return RtpShapeSampler.sample(profile, random, inner, outer);
    }

    boolean contains(double x, double z) {
        double radius = RtpShapeSampler.radiusAt(profile, x, z);
        return radius >= inner && radius <= outer;
    }

    void rejected(String reason, double x, double z) {
        double radius = RtpShapeSampler.radiusAt(profile, x, z);
        if (MysticRpgRtpSafety.ABOVE_RANGE.equals(reason)) {
            // Exclude the known-too-high radius and everything beyond it.
            outer = Math.min(outer, Math.max(inner + MINIMUM_BAND_WIDTH, radius - 1.0));
        } else if (MysticRpgRtpSafety.BELOW_RANGE.equals(reason)) {
            // Exclude the known-too-low radius and everything inside it.
            inner = Math.max(inner, Math.min(outer - MINIMUM_BAND_WIDTH, radius + 1.0));
        }
    }

    double inner() {
        return inner;
    }

    double outer() {
        return outer;
    }
}
