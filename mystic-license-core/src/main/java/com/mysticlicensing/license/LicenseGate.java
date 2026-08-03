package com.mysticlicensing.license;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The one class a mod actually touches.
 *
 * <p>Construct it during mod initialisation, call {@link #start()} once, then
 * ask it questions. It verifies the license file exactly once, caches the
 * immutable result, and answers every subsequent query from memory - so
 * {@link #hasFeature} is a map lookup, cheap enough to call from game code.
 *
 * <pre>{@code
 * LicenseGate license = LicenseGate.builder(Products.ESSENTIALS)
 *         .dataDir(modDataDirectory)
 *         .modVersion("1.0.1")
 *         .serverName(serverDisplayName)   // optional, cosmetic
 *         .logger(myLoggerAdapter)
 *         .build();
 *
 * license.start();
 *
 * if (license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT)) {
 *     registerCustomContentModule();
 * }
 * }</pre>
 *
 * <h2>Failure policy</h2>
 * Nothing here throws. Every bad argument, IO failure, corrupt file and
 * verification failure ends as a {@link LicenseStatus} plus one log line. A
 * licensing problem switches off a licensed feature; it never stops the mod
 * loading and never takes the server down. That includes a misconfigured build:
 * if the embedded keys do not parse, the gate reports
 * {@link LicenseStatus#INVALID_FORMAT} and grants nothing rather than throwing
 * out of the constructor.
 *
 * <h2>Thread safety</h2>
 * {@link #start()} and {@link #reload()} publish an immutable snapshot through a
 * volatile field. Readers on game threads always see a complete, consistent
 * state - never a half-initialised one. Verification is never done on a timer;
 * {@link #reload()} is the only way to re-read, and it is meant for an admin
 * command.
 */
public final class LicenseGate implements MysticLicenseService {

    /** Default license file name inside the mod's data directory. */
    public static final String LICENSE_FILE = "license.mclicense";

    /** Immutable snapshot, published atomically. */
    private record State(LicenseStatus status,
                         LicensePayload payload,
                         String detail,
                         UUID serverUuid) {

        static State of(LicenseStatus status, String detail, UUID serverUuid) {
            return new State(status, null, detail, serverUuid);
        }
    }

    private final String productId;
    private final Path dataDir;
    private final Supplier<Path> licenseFile;
    private final Supplier<String> serverUuidSupplier;
    private final String modVersion;
    private final String serverName;
    private final LicenseLog log;
    private final Clock clock;
    private final boolean writeRequestFile;

    /** Null when the keys failed to load; see {@link #keyFailure}. */
    private final McLicenseVerifier verifier;
    private final String keyFailure;

    private volatile State state = State.of(LicenseStatus.MISSING, "not started", null);

    /** Grace reminder is logged at most once per gate instance. */
    private volatile boolean graceReminderLogged;

    private LicenseGate(Builder builder, McLicenseVerifier verifier, String keyFailure) {
        this.productId = builder.productId;
        this.dataDir = builder.dataDir;
        this.licenseFile = builder.licenseFile;
        this.serverUuidSupplier = builder.serverUuid;
        this.modVersion = builder.modVersion;
        this.serverName = builder.serverName;
        this.log = LicenseLog.guarded(builder.log);
        this.clock = builder.clock;
        this.writeRequestFile = builder.writeRequestFile;
        this.verifier = verifier;
        this.keyFailure = keyFailure;
    }

    public static Builder builder(String productId) {
        return new Builder(productId);
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Resolve the server identity, verify the license, log one summary line.
     *
     * <p>Call once from mod init. Returns the resulting status so callers that
     * want to branch on it need not read a field.
     */
    public LicenseStatus start() {
        LicenseStatus result = load();
        logSummary();
        return result;
    }

    /**
     * Re-read and re-verify. Wire this to an admin command so an operator can
     * drop in a renewed license without restarting the server.
     */
    public LicenseStatus reload() {
        graceReminderLogged = false;
        LicenseStatus result = load();
        logSummary();
        return result;
    }

    private LicenseStatus load() {
        try {
            state = verifyNow();
        } catch (Throwable t) {
            // Defensive. Nothing below is written to throw, but a bug in the
            // licensing code must never be able to take a server down.
            state = State.of(LicenseStatus.INVALID_FORMAT, "licensing error: " + t, state.serverUuid());
        }
        return state.status();
    }

    private State verifyNow() {
        if (verifier == null) {
            return State.of(LicenseStatus.INVALID_FORMAT, keyFailure, null);
        }

        // --- 1. server identity ---------------------------------------------
        UUID serverUuid = null;
        String resolved = serverUuidSupplier == null ? null : callSupplier(serverUuidSupplier);
        if (resolved != null) {
            serverUuid = parseUuid(resolved);
            if (serverUuid == null) {
                log.warn(prefixed("Configured server id '" + resolved + "' is not a UUID; "
                        + "the server binding cannot be checked."));
            }
        } else {
            ServerIdentity.Result identity = ServerIdentity.resolve(dataDir);
            if (identity.detail() != null) {
                log.warn(prefixed(identity.detail()));
            }
            if (identity.outcome() == ServerIdentity.Outcome.CREATED) {
                log.info(prefixed("Generated this server's licensing id: " + identity.uuid()
                        + " (stored in " + dataDir.resolve(ServerIdentity.IDENTITY_FILE) + ")"));
            }
            serverUuid = identity.uuid();
        }

        // --- 2. verify --------------------------------------------------------
        Path file = licenseFile == null ? dataDir.resolve(LICENSE_FILE) : callSupplier(licenseFile);
        String uuidText = serverUuid == null ? null : serverUuid.toString().toLowerCase(Locale.ROOT);
        LicenseCheckResult result = verifier.verifyFile(file, uuidText, clock.instant());

        // A null uuid tells the verifier to skip the binding check, which is the
        // right answer for an unbound or discord_user license and the wrong one
        // for a server_uuid license: we would be granting a binding we could not
        // actually check. Unknown identity plus a bound license is WRONG_SERVER,
        // and the warning above already says why the identity is unknown.
        if (serverUuid == null
                && result.grantsAccess()
                && result.payload() != null
                && "server_uuid".equals(result.payload().bindingMode())) {
            result = LicenseCheckResult.of(LicenseStatus.WRONG_SERVER, result.payload(),
                    "this server's licensing id could not be determined, so a license bound to "
                            + result.payload().serverUuids() + " cannot be checked");
        }

        // --- 3. help the operator get a license --------------------------------
        if (writeRequestFile && result.status() == LicenseStatus.MISSING && serverUuid != null) {
            try {
                Path request = ServerIdentity.writeLicenseRequest(
                        dataDir, serverUuid, serverName, productId, modVersion);
                log.info(prefixed("No license found. Upload " + request
                        + " to the licensing portal to register this server."));
            } catch (IOException | RuntimeException e) {
                log.warn(prefixed("Could not write a license request file: " + e.getMessage()));
            }
        }

        return new State(result.status(), result.payload(), result.detail(), serverUuid);
    }

    // ------------------------------------------------- MysticLicenseService

    @Override
    public LicenseStatus status() {
        return state.status();
    }

    @Override
    public boolean isValid() {
        return state.status().grantsAccess();
    }

    @Override
    public boolean isProductLicensed(String product) {
        State current = state;
        return current.status().grantsAccess()
                && current.payload() != null
                && current.payload().coversProduct(product);
    }

    @Override
    public boolean hasFeature(String product, String featureId) {
        State current = state;
        return current.status().grantsAccess()
                && current.payload() != null
                && current.payload().coversFeature(product, featureId);
    }

    @Override
    public Optional<Instant> expiresAt() {
        LicensePayload payload = state.payload();
        return payload == null ? Optional.empty() : payload.expiresAt();
    }

    @Override
    public Optional<String> licenseId() {
        LicensePayload payload = state.payload();
        return payload == null ? Optional.empty() : Optional.ofNullable(payload.licenseId());
    }

    // ---------------------------------------------------------- convenience

    /** This mod's own product id, so callers need not repeat it. */
    public boolean hasFeature(String featureId) {
        return hasFeature(productId, featureId);
    }

    /**
     * Run {@code enable} only when the feature is licensed, logging the decision
     * once. Keeps module registration declarative instead of scattering
     * {@code if} statements through the codebase.
     *
     * <p>An exception thrown by {@code enable} is the caller's problem and
     * propagates normally - this method only decides whether to run it.
     */
    public void whenLicensed(String featureId, Runnable enable) {
        if (hasFeature(featureId)) {
            enable.run();
        } else {
            log.info(prefixed("Feature '" + featureId + "' is not licensed and stays disabled ("
                    + status() + ")."));
        }
    }

    /** The product id this gate was built for. */
    public String productId() {
        return productId;
    }

    /** Null only when the identity file is corrupt and no override was supplied. */
    public UUID serverUuid() {
        return state.serverUuid();
    }

    /** Human-readable detail from the last verification, for an admin command. */
    public Optional<String> detail() {
        String detail = state.detail();
        return detail == null || detail.isBlank() ? Optional.empty() : Optional.of(detail);
    }

    /** Features of this product that the current license actually grants. */
    public List<String> licensedFeatures(List<String> candidates) {
        return candidates.stream().filter(this::hasFeature).toList();
    }

    /** One-line human summary, suitable for an admin command. */
    public String summaryLine() {
        State current = state;
        StringBuilder out = new StringBuilder(160);
        out.append('[').append(productId).append("] License: ").append(current.status());

        LicensePayload payload = current.payload();
        if (payload != null) {
            out.append(" | id=").append(payload.licenseId());
            out.append(" | type=").append(payload.licenseType());
            out.append(" | expires=")
                    .append(payload.expiresAt().map(Instant::toString).orElse("never"));
        }
        if (current.serverUuid() != null) {
            out.append(" | server=").append(current.serverUuid());
        }
        if (current.detail() != null && !current.detail().isBlank()) {
            out.append(" | ").append(current.detail());
        }
        return out.toString();
    }

    // ------------------------------------------------------------- logging

    /**
     * Exactly one startup line, with an actionable follow-up when something is
     * wrong. Deliberately not logged per feature check.
     */
    private void logSummary() {
        State current = state;
        if (current.status().grantsAccess()) {
            log.info(summaryLine());
            if (current.status() == LicenseStatus.GRACE_PERIOD && !graceReminderLogged) {
                graceReminderLogged = true;
                log.warn(prefixed("This license has expired and is running on its grace period. "
                        + "Renew it in the portal before the grace period ends."));
            }
            if (!isProductLicensed(productId)) {
                log.warn(prefixed("The license is valid but does not cover this product. "
                        + "Its features stay disabled."));
            }
            return;
        }

        log.warn(summaryLine());
        String advice = advice(current.status());
        if (!advice.isEmpty()) {
            log.warn(prefixed(advice));
        }
    }

    private String advice(LicenseStatus status) {
        return switch (status) {
            case MISSING -> "Place " + LICENSE_FILE + " in " + dataDir
                    + " and restart. Everything else keeps working.";
            case WRONG_SERVER -> state.serverUuid() == null
                    ? "This license is bound to a specific server, and this server's licensing id "
                            + "could not be read. Fix or delete " + dataDir.resolve(ServerIdentity.IDENTITY_FILE)
                            + ", then re-register the server in the portal."
                    : "This license is bound to a different server UUID. This server's id is "
                            + state.serverUuid() + ". Use the portal's server-replacement flow to move it.";
            case EXPIRED -> "The license and its grace period have both ended. Renew it in the portal.";
            case NOT_YET_VALID -> "The license is not valid yet. Check this machine's system clock.";
            case INVALID_SIGNATURE -> "The license file failed signature verification. Re-download it; "
                    + "do not edit it by hand.";
            case DECRYPTION_FAILED, UNKNOWN_ENCRYPTION_KEY -> "This build cannot read that license. "
                    + "Update the mod to a version that carries the matching key.";
            case UNKNOWN_SIGNING_KEY -> "This license was signed by a key this build does not trust. "
                    + "Update the mod.";
            case UNSUPPORTED_VERSION -> "This license uses a newer format than this build understands. "
                    + "Update the mod.";
            case WRONG_PRODUCT -> "This license does not cover this product.";
            case INVALID_FORMAT -> "The license file is not readable. Re-download it from the portal.";
            case VALID, GRACE_PERIOD -> "";
        };
    }

    private String prefixed(String message) {
        return "[" + productId + "] " + message;
    }

    // ----------------------------------------------------------------- util

    private <T> T callSupplier(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            log.warn(prefixed("A licensing supplier failed: " + e));
            return null;
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --------------------------------------------------------------- builder

    /**
     * Collects the three integration points a mod has to supply - where the
     * license lives, what this server's UUID is, and how to log - plus the
     * cosmetic bits that make a support ticket answerable.
     *
     * <p>Every one has a working default, so the minimum is
     * {@code LicenseGate.builder(product).dataDir(dir).build()}.
     */
    public static final class Builder {
        private final String productId;
        private Path dataDir = Path.of(".");
        private Supplier<Path> licenseFile;
        private Supplier<String> serverUuid;
        private String modVersion = "unknown";
        private String serverName;
        private LicenseLog log = LicenseLog.system("mystic-license");
        private Clock clock = Clock.systemUTC();
        private boolean writeRequestFile = true;
        private boolean useEmbeddedKeys = true;
        private McLicenseVerifier.Builder keys = McLicenseVerifier.builder();

        private Builder(String productId) {
            this.productId = Objects.requireNonNull(productId, "productId");
        }

        /**
         * The mod's data directory. Unless overridden, {@code license.mclicense}
         * and {@code server-id.txt} live here.
         */
        public Builder dataDir(Path value) {
            this.dataDir = Objects.requireNonNull(value, "dataDir");
            return this;
        }

        /** Override where the license file is read from. Defaults to the data dir. */
        public Builder licenseFile(Supplier<Path> value) {
            this.licenseFile = value;
            return this;
        }

        /**
         * Override how this server's UUID is determined. Defaults to
         * {@link ServerIdentity}, which persists one in the data directory.
         * The value is normalised to lowercase before the binding check.
         */
        public Builder serverUuid(Supplier<String> value) {
            this.serverUuid = value;
            return this;
        }

        public Builder modVersion(String value) {
            this.modVersion = value;
            return this;
        }

        /** Cosmetic, written into license-request.json to help the operator. */
        public Builder serverName(String value) {
            this.serverName = value;
            return this;
        }

        public Builder logger(LicenseLog value) {
            this.log = Objects.requireNonNull(value, "logger");
            return this;
        }

        /** Fixed clock for tests. Defaults to {@link Clock#systemUTC()}. */
        public Builder clock(Clock value) {
            this.clock = Objects.requireNonNull(value, "clock");
            return this;
        }

        /** Disable writing license-request.json when no license is present. */
        public Builder writeRequestFile(boolean value) {
            this.writeRequestFile = value;
            return this;
        }

        /**
         * Trust an additional signing key id. Calling this does not remove the
         * keys from {@link EmbeddedKeys}; see {@link #withoutEmbeddedKeys()}.
         */
        public Builder trustSigningKey(String keyId, String spkiBase64) {
            keys.trustSigningKey(keyId, spkiBase64);
            return this;
        }

        public Builder addContentKey(String keyId, String keyBase64Url) {
            keys.addContentKey(keyId, keyBase64Url);
            return this;
        }

        /**
         * Do not register {@link EmbeddedKeys}. Only useful in tests, which sign
         * with their own throwaway keypair.
         */
        public Builder withoutEmbeddedKeys() {
            this.useEmbeddedKeys = false;
            return this;
        }

        public LicenseGate build() {
            // A bad key is a build-time mistake, but it must not surface as an
            // exception on a live server. Capture it and report MISSING-like
            // behaviour instead: the mod loads, the licensed feature stays off.
            try {
                McLicenseVerifier.Builder builder = keys;
                if (useEmbeddedKeys) {
                    EmbeddedKeys.trustAll(builder);
                }
                return new LicenseGate(this, builder.build(), null);
            } catch (RuntimeException e) {
                return new LicenseGate(this, null,
                        "this build's embedded licensing keys are unusable: " + e.getMessage());
            }
        }
    }
}
