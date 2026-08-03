package com.mysticlicensing.license;

import java.util.Optional;

/**
 * What one verification attempt concluded: a {@link LicenseStatus}, the payload
 * when there was one, and a short human-readable detail for the startup log.
 *
 * <p>{@code payload} is non-null whenever the file decrypted successfully, even
 * if a later check failed - an {@code EXPIRED} or {@code WRONG_SERVER} result
 * still carries the license id and dates, which is exactly what an operator
 * needs to see in the log to fix the problem.
 */
public record LicenseCheckResult(LicenseStatus status, LicensePayload payload, String detail) {

    public LicenseCheckResult {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
    }

    static LicenseCheckResult failure(LicenseStatus status, String detail) {
        return new LicenseCheckResult(status, null, detail);
    }

    static LicenseCheckResult of(LicenseStatus status, LicensePayload payload, String detail) {
        return new LicenseCheckResult(status, payload, detail);
    }

    /** Empty unless the file decrypted. */
    public Optional<LicensePayload> licensePayload() {
        return Optional.ofNullable(payload);
    }

    /** Convenience for {@code status().grantsAccess()}. */
    public boolean grantsAccess() {
        return status.grantsAccess();
    }

    @Override
    public String toString() {
        return status + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
    }
}
