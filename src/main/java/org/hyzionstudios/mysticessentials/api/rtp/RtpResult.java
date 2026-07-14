package org.hyzionstudios.mysticessentials.api.rtp;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/**
 * Outcome of a {@link RandomTeleportService#teleport} call. Never completes
 * exceptionally for an expected failure — inspect {@link #status()} instead.
 */
public record RtpResult(
        RtpStatus status,
        String profileId,
        MysticLocation destination,
        int attempts,
        long durationMillis,
        RtpCancelReason cancelReason,
        String detail) {

    public static RtpResult success(String profileId, MysticLocation destination, int attempts, long durationMillis) {
        return new RtpResult(RtpStatus.SUCCESS, profileId, destination, attempts, durationMillis, null, null);
    }

    public static RtpResult failure(RtpStatus status, String profileId, String detail) {
        return new RtpResult(status, profileId, null, 0, 0L, null, detail);
    }

    public static RtpResult cancelled(String profileId, RtpCancelReason reason) {
        return new RtpResult(RtpStatus.CANCELLED, profileId, null, 0, 0L, reason, null);
    }

    public boolean isSuccess() {
        return status == RtpStatus.SUCCESS;
    }
}
