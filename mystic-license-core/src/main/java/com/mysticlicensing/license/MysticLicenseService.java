package com.mysticlicensing.license;

import java.time.Instant;
import java.util.Optional;

/**
 * The interface a licensed mod should depend on.
 *
 * <p>Keeping the mod's feature code behind this interface means the licensing
 * implementation can be swapped (or stubbed in tests) without touching gameplay
 * code, and it makes the failure policy explicit: every method has a safe,
 * non-throwing answer when there is no valid license.
 *
 * <p><b>Failure policy.</b> When {@link #isValid()} is false the mod must
 * disable only the licensed feature or the licensed internal module. It must
 * not refuse to load, crash the server, or disable unlicensed functionality.
 */
public interface MysticLicenseService {

    /** Current status. Never null; {@link LicenseStatus#MISSING} when absent. */
    LicenseStatus status();

    /** Convenience for {@code status().grantsAccess()}. */
    boolean isValid();

    /** True when this product is covered, honouring the {@code *} wildcard. */
    boolean isProductLicensed(String productId);

    /** True when the product is covered and the feature is listed (or {@code *}). */
    boolean hasFeature(String productId, String featureId);

    /** Empty for a non-expiring license, or when there is no valid license. */
    Optional<Instant> expiresAt();

    /** The {@code license_id} of the loaded license, for support and logging. */
    Optional<String> licenseId();
}
