package com.mysticlicensing.license;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The decrypted license contents, in the shape a mod actually needs.
 *
 * <p>Deeply immutable and safely publishable: every collection is copied and
 * wrapped at construction, every field is final, so a reference handed to a
 * game thread can be read without synchronisation.
 *
 * <p>Fields the mod has no business acting on ({@code subject},
 * {@code metadata}, {@code supersedes_license_id}) are deliberately not
 * modelled. They exist in the payload for the portal's benefit; parsing them
 * here would only create things to get wrong.
 */
public final class LicensePayload {

    /** Wildcard token, valid both as a product id and as a feature id. */
    public static final String WILDCARD = "*";

    private final String licenseId;
    private final String licenseType;
    private final String issuer;
    private final String bindingMode;
    private final List<String> serverUuids;
    private final String boundDiscordUserId;
    private final Map<String, List<String>> products;
    private final Instant issuedAt;
    private final Instant notBefore;
    private final Instant expiresAt;
    private final long gracePeriodSeconds;
    private final int generation;

    LicensePayload(String licenseId,
                   String licenseType,
                   String issuer,
                   String bindingMode,
                   List<String> serverUuids,
                   String boundDiscordUserId,
                   Map<String, List<String>> products,
                   Instant issuedAt,
                   Instant notBefore,
                   Instant expiresAt,
                   long gracePeriodSeconds,
                   int generation) {
        this.licenseId = licenseId;
        this.licenseType = licenseType;
        this.issuer = issuer;
        this.bindingMode = bindingMode == null ? "unbound" : bindingMode;
        this.serverUuids = List.copyOf(serverUuids);
        this.boundDiscordUserId = boundDiscordUserId;

        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : products.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.products = Collections.unmodifiableMap(copy);

        this.issuedAt = issuedAt;
        this.notBefore = notBefore;
        this.expiresAt = expiresAt;
        this.gracePeriodSeconds = Math.max(0L, gracePeriodSeconds);
        this.generation = generation;
    }

    public String licenseId() {
        return licenseId;
    }

    public String licenseType() {
        return licenseType;
    }

    public String issuer() {
        return issuer;
    }

    /** {@code server_uuid}, {@code discord_user} or {@code unbound}. */
    public String bindingMode() {
        return bindingMode;
    }

    /** Lowercased server UUIDs this license is bound to. Empty unless bound. */
    public List<String> serverUuids() {
        return serverUuids;
    }

    public Optional<String> boundDiscordUserId() {
        return Optional.ofNullable(boundDiscordUserId);
    }

    /** Product id to feature ids, exactly as issued, wildcards included. */
    public Map<String, List<String>> products() {
        return products;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant notBefore() {
        return notBefore;
    }

    /** Empty means the license does not expire. */
    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    /** Null-returning accessor for internal use where {@code Optional} is noise. */
    Instant expiresAtOrNull() {
        return expiresAt;
    }

    public long gracePeriodSeconds() {
        return gracePeriodSeconds;
    }

    public int generation() {
        return generation;
    }

    /** Wildcard-aware product check. */
    public boolean coversProduct(String productId) {
        if (productId == null) {
            return false;
        }
        return products.containsKey(WILDCARD) || products.containsKey(productId);
    }

    /**
     * Wildcard-aware feature check.
     *
     * <p>Both levels wildcard independently, so all four of these grant
     * {@code (mysticessentials, module.customcontent)}:
     * <pre>
     *   {"*":                ["*"]}                        global license
     *   {"*":                ["module.customcontent"]}     that feature, any product
     *   {"mysticessentials": ["*"]}                        whole product
     *   {"mysticessentials": ["module.customcontent"]}     explicit grant
     * </pre>
     */
    public boolean coversFeature(String productId, String featureId) {
        if (productId == null || featureId == null) {
            return false;
        }
        List<String> wildcardProduct = products.get(WILDCARD);
        if (wildcardProduct != null
                && (wildcardProduct.contains(WILDCARD) || wildcardProduct.contains(featureId))) {
            return true;
        }
        List<String> features = products.get(productId);
        return features != null && (features.contains(WILDCARD) || features.contains(featureId));
    }

    /**
     * True when this license permits use on {@code serverUuid}.
     *
     * <p>{@code unbound} passes anywhere. {@code discord_user} also passes: the
     * mod has no way to learn the operator's Discord id, so enforcing it here
     * would reject every legitimate license of that kind. The binding is still
     * meaningful - the portal enforces it at issue time.
     */
    public boolean allowsServer(String serverUuid) {
        if (!"server_uuid".equals(bindingMode)) {
            return true;
        }
        if (serverUuid == null) {
            return true;
        }
        return serverUuids.contains(serverUuid.toLowerCase(Locale.ROOT));
    }
}
