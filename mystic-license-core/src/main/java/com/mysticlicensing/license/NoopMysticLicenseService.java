package com.mysticlicensing.license;

import java.time.Instant;
import java.util.Optional;

/**
 * A {@link MysticLicenseService} that grants nothing and reports
 * {@link LicenseStatus#MISSING}.
 *
 * <p>Two uses. In tests it is the honest stand-in for "this server has no
 * license", so feature code can be exercised in its locked state without
 * touching disk or crypto. In an unlicensed build it is what
 * {@code LicenseGate} is replaced by, which keeps every call site identical
 * whether or not licensing is compiled in.
 *
 * <p>Stateless and immutable, so {@link #INSTANCE} is safe to share.
 */
public final class NoopMysticLicenseService implements MysticLicenseService {

    public static final NoopMysticLicenseService INSTANCE = new NoopMysticLicenseService();

    public NoopMysticLicenseService() {
    }

    @Override
    public LicenseStatus status() {
        return LicenseStatus.MISSING;
    }

    @Override
    public boolean isValid() {
        return false;
    }

    @Override
    public boolean isProductLicensed(String productId) {
        return false;
    }

    @Override
    public boolean hasFeature(String productId, String featureId) {
        return false;
    }

    @Override
    public Optional<Instant> expiresAt() {
        return Optional.empty();
    }

    @Override
    public Optional<String> licenseId() {
        return Optional.empty();
    }
}
