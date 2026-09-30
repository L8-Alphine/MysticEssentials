package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.util.UUID;
import java.util.Random;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.rtp.RtpProfile;

/** Dependency-free checks for MysticRPG RTP level-band decisions. */
public final class MysticRpgRtpSafetyTest {

    private MysticRpgRtpSafetyTest() {
    }

    public static void main(String[] args) {
        acceptsConfiguredBand();
        rejectsLevelOnePlayerFromLevelHundredContent();
        rejectsTrivialContentBelowBand();
        handlesReversedOffsets();
        normalizesWorldNamesLikeMysticRpg();
        remainsOptionalWhenMysticRpgIsAbsent();
        narrowsInwardAfterHighLevelContent();
        narrowsOutwardAfterLowLevelContent();
    }

    private static void acceptsConfiguredBand() {
        require(reason(20, 10, -10, 3) == null, "lower edge was rejected");
        require(reason(20, 23, -10, 3) == null, "upper edge was rejected");
        require(reason(1, 1, -10, 3) == null, "level floor was not clamped");
    }

    private static void rejectsLevelOnePlayerFromLevelHundredContent() {
        require(MysticRpgRtpSafety.ABOVE_RANGE.equals(reason(1, 100, -10, 3)),
                "level 100 content was accepted for a level 1 player");
    }

    private static void rejectsTrivialContentBelowBand() {
        require(MysticRpgRtpSafety.BELOW_RANGE.equals(reason(50, 39, -10, 3)),
                "content below the configured range was accepted");
    }

    private static void handlesReversedOffsets() {
        require(reason(20, 15, 3, -10) == null, "reversed offsets changed the band");
        require(MysticRpgRtpSafety.ABOVE_RANGE.equals(reason(20, 24, 3, -10)),
                "reversed offsets changed the upper rejection");
    }

    private static void normalizesWorldNamesLikeMysticRpg() {
        require("trial_halls".equals(MysticRpgRtpSafety.normalizeWorld(" Trial Halls ")),
                "spaces were not normalized");
        require("default".equals(MysticRpgRtpSafety.normalizeWorld(" ")),
                "blank world did not fall back to default");
        require(MysticRpgRtpSafety.normalizeWorld("A".repeat(80)).length() == 64,
                "world key was not capped at MysticRPG's segment limit");
    }

    private static void remainsOptionalWhenMysticRpgIsAbsent() {
        RandomTeleportConfig.MysticRpgSafety settings =
                new RandomTeleportConfig.MysticRpgSafety();
        String rejection = new MysticRpgRtpSafety(null).reject(UUID.randomUUID(),
                new MysticLocation("default", 0, 64, 0, 0, 0), settings);
        require(rejection == null, "an absent optional MysticRPG dependency blocked RTP");
    }

    private static void narrowsInwardAfterHighLevelContent() {
        RtpProfile profile = profile();
        RtpLevelSearchRange range = new RtpLevelSearchRange(profile);
        range.rejected(MysticRpgRtpSafety.ABOVE_RANGE, 8_000, 0);

        require(range.outer() < 8_000, "a high-level rejection did not move the search inward");
        for (int attempt = 0; attempt < 100; attempt++) {
            double[] point = range.sample(new Random(attempt));
            require(Math.hypot(point[0], point[1]) <= range.outer() + 0.001,
                    "the narrowed sampler returned another outer-area point");
        }
    }

    private static void narrowsOutwardAfterLowLevelContent() {
        RtpProfile profile = profile();
        RtpLevelSearchRange range = new RtpLevelSearchRange(profile);
        range.rejected(MysticRpgRtpSafety.BELOW_RANGE, 2_000, 0);

        require(range.inner() > 2_000, "a low-level rejection did not move the search outward");
        for (int attempt = 0; attempt < 100; attempt++) {
            double[] point = range.sample(new Random(attempt));
            require(Math.hypot(point[0], point[1]) >= range.inner() - 0.001,
                    "the narrowed sampler returned another inner-area point");
        }
    }

    private static RtpProfile profile() {
        RtpProfile profile = new RtpProfile();
        profile.minimumRadius = 20;
        profile.maximumRadius = 15_000;
        profile.borderPadding = 128;
        return profile;
    }

    private static String reason(int player, int content, int low, int high) {
        return MysticRpgRtpSafety.rejectionReason(player, content, low, high);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
