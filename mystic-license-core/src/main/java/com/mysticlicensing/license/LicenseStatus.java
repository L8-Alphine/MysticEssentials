package com.mysticlicensing.license;

/**
 * Outcome of validating a {@code license.mclicense} file.
 *
 * <p>A mod must treat every non-{@link #VALID}/{@link #GRACE_PERIOD} value as
 * "the licensed feature stays off" - never as "shut the whole mod down". A
 * missing or broken license file is a licensing problem, not a reason to break
 * somebody's server.
 */
public enum LicenseStatus {

    /** Signature verified, decrypted, in date, bound to this server. */
    VALID,

    /** Expired, but still inside the grace period recorded in the payload. */
    GRACE_PERIOD,

    /** No license file was found in the configured data directory. */
    MISSING,

    /** The file is not a well-formed MCL1 envelope. */
    INVALID_FORMAT,

    /** Ed25519 verification failed: the file was tampered with or forged. */
    INVALID_SIGNATURE,

    /** AES-256-GCM authenticated decryption failed. */
    DECRYPTION_FAILED,

    /** The license is bound to a different Hytale server UUID. */
    WRONG_SERVER,

    /** The license does not cover the product that asked. */
    WRONG_PRODUCT,

    /** The current time is before the payload's {@code not_before}. */
    NOT_YET_VALID,

    /** Past expiry and past the grace period. */
    EXPIRED,

    /** Envelope or payload format version this build does not understand. */
    UNSUPPORTED_VERSION,

    /** The envelope names a signing key id this build does not trust. */
    UNKNOWN_SIGNING_KEY,

    /** The envelope names a content key id this build does not carry. */
    UNKNOWN_ENCRYPTION_KEY;

    /** True when licensed features should be switched on. */
    public boolean grantsAccess() {
        return this == VALID || this == GRACE_PERIOD;
    }
}
