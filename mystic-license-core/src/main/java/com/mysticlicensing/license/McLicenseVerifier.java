package com.mysticlicensing.license;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Verifies a {@code license.mclicense} file. Offline, always: this class opens
 * no sockets and has no notion of a licensing server.
 *
 * <p>Stateless once built and safe to share between threads.
 *
 * <h2>Order of operations, and why it is fixed</h2>
 * <ol>
 *   <li>Read the file, refusing implausibly large ones before allocating.</li>
 *   <li>Parse the envelope; check {@code magic} and {@code version}.</li>
 *   <li>Resolve {@code signing_key_id} among the trusted public keys.</li>
 *   <li>Rebuild the signed byte sequence.</li>
 *   <li><b>Verify the Ed25519 signature. Stop here on failure.</b></li>
 *   <li>Resolve {@code encryption_key_id}.</li>
 *   <li>Decrypt AES-256-GCM.</li>
 *   <li>Parse the payload; check {@code format} and {@code format_version}.</li>
 *   <li>Check {@code not_before}.</li>
 *   <li>Check {@code expires_at} plus {@code grace_period_seconds}.</li>
 *   <li>Check the server UUID binding.</li>
 * </ol>
 *
 * <p>Step 5 before step 7 is the whole point. The ciphertext is attacker-supplied
 * until the signature says otherwise, so no unauthenticated byte is allowed to
 * reach the JSON parser or the AES implementation. The
 * {@link ContentKeySource} seam exists so a test can prove the content key is
 * not so much as requested when a signature fails.
 *
 * <h2>Threat model, stated plainly</h2>
 * The AES content key ships inside the mod, so assume it can be extracted. That
 * only reveals what a license says. Creating a license this verifier accepts
 * requires the Ed25519 private key, which never leaves the licensing server. A
 * determined person can of course patch the mod's bytecode; this is a licensing
 * control for honest operators, not DRM.
 *
 * <h2>Failure policy</h2>
 * Nothing here throws. Every malformed file, IO failure and crypto failure
 * becomes a {@link LicenseCheckResult}.
 */
public final class McLicenseVerifier {

    private static final String MAGIC = "MCL1";
    private static final int ENVELOPE_VERSION = 1;
    private static final int PAYLOAD_FORMAT_VERSION = 1;
    private static final String PAYLOAD_FORMAT = "mystic-license";
    private static final String ENCRYPTION_ALGORITHM = "AES-256-GCM";
    private static final String SIGNATURE_ALGORITHM = "Ed25519";

    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int TAG_BITS = TAG_BYTES * Byte.SIZE;
    private static final int CONTENT_KEY_BYTES = 32;

    /** Refuse absurdly large files before allocating anything. */
    static final long MAX_FILE_BYTES = 256L * 1024L;

    private final Map<String, PublicKey> trustedSigningKeys;
    private final ContentKeySource contentKeys;

    private McLicenseVerifier(Map<String, PublicKey> signingKeys, ContentKeySource contentKeys) {
        this.trustedSigningKeys = Map.copyOf(signingKeys);
        this.contentKeys = contentKeys;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------- builder

    /** Collects the keys a mod build embeds. */
    public static final class Builder {
        private final Map<String, PublicKey> signingKeys = new LinkedHashMap<>();
        private final Map<String, byte[]> contentKeys = new LinkedHashMap<>();
        private ContentKeySource contentKeySource;

        private Builder() {
        }

        /**
         * Trust a signing key. Several may be trusted at once, so rotating the
         * portal's key does not invalidate licenses already in the field: ship
         * a build that trusts both, wait out the old licenses, then drop the old
         * key in a later release.
         *
         * @param keyId      value of {@code signing_key_id} in the envelope
         * @param spkiBase64 standard-alphabet base64 of the SPKI DER, as printed
         *                   by {@code npm run keys:show-public} in the portal
         * @throws IllegalArgumentException if the key does not parse. This is a
         *                                  build-time programming error, not a
         *                                  runtime licensing outcome, so it is
         *                                  the one place that throws - and
         *                                  {@code LicenseGate} catches it.
         */
        public Builder trustSigningKey(String keyId, String spkiBase64) {
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(spkiBase64, "spkiBase64");
            try {
                byte[] der = Base64.getDecoder().decode(stripPem(spkiBase64));
                PublicKey key = KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(der));
                signingKeys.put(keyId, key);
                return this;
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid Ed25519 public key for " + keyId, e);
            }
        }

        /**
         * Add an AES-256 content key.
         *
         * @param keyId        value of {@code encryption_key_id}
         * @param keyBase64Url unpadded base64url of the 32 key bytes
         */
        public Builder addContentKey(String keyId, String keyBase64Url) {
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(keyBase64Url, "keyBase64Url");
            byte[] key = Base64.getUrlDecoder().decode(keyBase64Url);
            if (key.length != CONTENT_KEY_BYTES) {
                throw new IllegalArgumentException(
                        "Content key " + keyId + " must be " + CONTENT_KEY_BYTES + " bytes");
            }
            contentKeys.put(keyId, key);
            return this;
        }

        /**
         * Replace the whole content-key lookup. Overrides {@link #addContentKey}.
         * Primarily a test seam; see {@link ContentKeySource}.
         */
        public Builder contentKeySource(ContentKeySource source) {
            this.contentKeySource = Objects.requireNonNull(source, "source");
            return this;
        }

        public McLicenseVerifier build() {
            if (signingKeys.isEmpty()) {
                throw new IllegalStateException("At least one trusted signing key is required");
            }
            ContentKeySource source = contentKeySource;
            if (source == null) {
                Map<String, byte[]> snapshot = new LinkedHashMap<>(contentKeys);
                // Hand out a copy: a caller must not be able to mutate the key
                // material we verify with.
                source = keyId -> {
                    byte[] key = snapshot.get(keyId);
                    return key == null ? null : key.clone();
                };
            }
            return new McLicenseVerifier(signingKeys, source);
        }

        private static String stripPem(String value) {
            if (!value.contains("-----")) {
                return value;
            }
            return value.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
        }
    }

    // ------------------------------------------------------------- verifying

    /**
     * Read and verify a license file. A missing file is a normal outcome, not
     * an error: it means this server has not been licensed yet.
     *
     * @param serverUuid this server's UUID, or null to skip the binding check
     * @param now        the instant to evaluate validity at, or null for the
     *                   system clock
     */
    public LicenseCheckResult verifyFile(Path file, String serverUuid, Instant now) {
        if (file == null) {
            return LicenseCheckResult.failure(LicenseStatus.MISSING, "no license path configured");
        }
        try {
            if (!Files.isRegularFile(file)) {
                return LicenseCheckResult.failure(LicenseStatus.MISSING, "no license file at " + file);
            }
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                return LicenseCheckResult.failure(LicenseStatus.INVALID_FORMAT,
                        "license file is implausibly large (" + size + " bytes)");
            }
            if (size == 0L) {
                return LicenseCheckResult.failure(LicenseStatus.INVALID_FORMAT, "license file is empty");
            }
            return verify(Files.readAllBytes(file), serverUuid, now);
        } catch (IOException | SecurityException e) {
            return LicenseCheckResult.failure(LicenseStatus.MISSING,
                    "cannot read license file: " + e.getMessage());
        }
    }

    /** Verify raw license bytes. See {@link #verifyFile} for the parameters. */
    public LicenseCheckResult verify(byte[] fileBytes, String serverUuid, Instant now) {
        if (fileBytes == null || fileBytes.length == 0) {
            return LicenseCheckResult.failure(LicenseStatus.INVALID_FORMAT, "license file is empty");
        }

        // --- 2. Parse and check the envelope --------------------------------
        Map<String, Object> envelope;
        try {
            envelope = MiniJson.asObject(MiniJson.parse(new String(fileBytes, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            return LicenseCheckResult.failure(LicenseStatus.INVALID_FORMAT, "envelope is not valid JSON");
        }
        if (envelope == null) {
            return LicenseCheckResult.failure(LicenseStatus.INVALID_FORMAT, "envelope is not an object");
        }

        if (!MAGIC.equals(MiniJson.asString(envelope.get("magic")))) {
            return LicenseCheckResult.failure(LicenseStatus.INVALID_FORMAT, "bad magic");
        }
        Long version = MiniJson.asLong(envelope.get("version"));
        if (version == null || version != ENVELOPE_VERSION) {
            return LicenseCheckResult.failure(LicenseStatus.UNSUPPORTED_VERSION,
                    "envelope version " + version);
        }

        Map<String, Object> algorithm = MiniJson.asObject(envelope.get("algorithm"));
        if (algorithm == null
                || !ENCRYPTION_ALGORITHM.equals(MiniJson.asString(algorithm.get("encryption")))
                || !SIGNATURE_ALGORITHM.equals(MiniJson.asString(algorithm.get("signature")))) {
            return LicenseCheckResult.failure(LicenseStatus.INVALID_FORMAT, "unsupported algorithms");
        }

        String signingKeyId = MiniJson.asString(envelope.get("signing_key_id"));
        String encryptionKeyId = MiniJson.asString(envelope.get("encryption_key_id"));
        String ivB64 = MiniJson.asString(envelope.get("iv"));
        String ciphertextB64 = MiniJson.asString(envelope.get("ciphertext"));
        String tagB64 = MiniJson.asString(envelope.get("authentication_tag"));
        String signatureB64 = MiniJson.asString(envelope.get("signature"));

        if (signingKeyId == null || encryptionKeyId == null || ivB64 == null
                || ciphertextB64 == null || tagB64 == null || signatureB64 == null) {
            return LicenseCheckResult.failure(LicenseStatus.INVALID_FORMAT,
                    "envelope is missing required fields");
        }

        // --- 3. Resolve the signing key -------------------------------------
        PublicKey publicKey = trustedSigningKeys.get(signingKeyId);
        if (publicKey == null) {
            return LicenseCheckResult.failure(LicenseStatus.UNKNOWN_SIGNING_KEY,
                    "untrusted signing key '" + signingKeyId + "'");
        }

        // --- 4 + 5. Rebuild the signed bytes and verify BEFORE decrypting ----
        byte[] signingInput = SigningInput.build(
                MAGIC, version, signingKeyId, encryptionKeyId,
                ENCRYPTION_ALGORITHM, SIGNATURE_ALGORITHM, ivB64, ciphertextB64, tagB64);

        try {
            byte[] signature = Base64.getUrlDecoder().decode(signatureB64);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(signingInput);
            if (!verifier.verify(signature)) {
                return LicenseCheckResult.failure(LicenseStatus.INVALID_SIGNATURE,
                        "signature does not match");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return LicenseCheckResult.failure(LicenseStatus.INVALID_SIGNATURE, describe(e));
        }

        // Past this line the envelope is authentic. Only now may its contents
        // be handed to the AES implementation and the JSON parser.

        // --- 6. Resolve the content key -------------------------------------
        byte[] contentKey;
        try {
            contentKey = contentKeys.keyFor(encryptionKeyId);
        } catch (RuntimeException e) {
            return LicenseCheckResult.failure(LicenseStatus.UNKNOWN_ENCRYPTION_KEY, describe(e));
        }
        if (contentKey == null) {
            return LicenseCheckResult.failure(LicenseStatus.UNKNOWN_ENCRYPTION_KEY,
                    "no content key '" + encryptionKeyId + "' in this build");
        }
        if (contentKey.length != CONTENT_KEY_BYTES) {
            return LicenseCheckResult.failure(LicenseStatus.UNKNOWN_ENCRYPTION_KEY,
                    "content key '" + encryptionKeyId + "' is not " + CONTENT_KEY_BYTES + " bytes");
        }

        // --- 7. Authenticated decryption ------------------------------------
        byte[] plaintext;
        try {
            byte[] iv = Base64.getUrlDecoder().decode(ivB64);
            byte[] ciphertext = Base64.getUrlDecoder().decode(ciphertextB64);
            byte[] tag = Base64.getUrlDecoder().decode(tagB64);
            if (iv.length != IV_BYTES || tag.length != TAG_BYTES) {
                return LicenseCheckResult.failure(LicenseStatus.INVALID_FORMAT, "bad iv or tag length");
            }

            // The format stores ciphertext and tag separately; Java's GCM
            // implementation wants them concatenated.
            byte[] combined = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
            System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(contentKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv)); // no AAD
            plaintext = cipher.doFinal(combined);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return LicenseCheckResult.failure(LicenseStatus.DECRYPTION_FAILED, describe(e));
        }
        // No attempt is made to scrub the key or plaintext from memory. The key
        // is a constant in this jar and the payload is not secret from the
        // operator; zeroing a copy would look like a security measure without
        // being one. See the README's honesty section.

        // --- 8. Parse the payload and check its version ----------------------
        LicensePayload payload;
        try {
            Map<String, Object> json =
                    MiniJson.asObject(MiniJson.parse(new String(plaintext, StandardCharsets.UTF_8)));
            if (json == null || !PAYLOAD_FORMAT.equals(MiniJson.asString(json.get("format")))) {
                return LicenseCheckResult.failure(LicenseStatus.INVALID_FORMAT,
                        "payload is not a mystic-license");
            }
            Long formatVersion = MiniJson.asLong(json.get("format_version"));
            if (formatVersion == null || formatVersion != PAYLOAD_FORMAT_VERSION) {
                return LicenseCheckResult.failure(LicenseStatus.UNSUPPORTED_VERSION,
                        "payload version " + formatVersion);
            }
            payload = readPayload(json);
        } catch (RuntimeException e) {
            return LicenseCheckResult.failure(LicenseStatus.INVALID_FORMAT, "payload could not be read");
        }

        // --- 9. not_before ---------------------------------------------------
        Instant checkTime = now == null ? Instant.now() : now;
        if (payload.notBefore() != null && checkTime.isBefore(payload.notBefore())) {
            return LicenseCheckResult.of(LicenseStatus.NOT_YET_VALID, payload,
                    "valid from " + payload.notBefore());
        }

        // --- 10. Expiry and grace period --------------------------------------
        LicenseStatus timeStatus = LicenseStatus.VALID;
        String detail = null;
        Instant expiresAt = payload.expiresAtOrNull();
        if (expiresAt != null && checkTime.isAfter(expiresAt)) {
            Instant graceEnd = expiresAt.plusSeconds(payload.gracePeriodSeconds());
            if (checkTime.isAfter(graceEnd)) {
                return LicenseCheckResult.of(LicenseStatus.EXPIRED, payload,
                        "expired at " + expiresAt);
            }
            timeStatus = LicenseStatus.GRACE_PERIOD;
            detail = "expired at " + expiresAt + ", grace period ends " + graceEnd;
        }

        // --- 11. Server binding ------------------------------------------------
        if (!payload.allowsServer(serverUuid)) {
            return LicenseCheckResult.of(LicenseStatus.WRONG_SERVER, payload,
                    "license is bound to " + payload.serverUuids());
        }

        return LicenseCheckResult.of(timeStatus, payload, detail);
    }

    /**
     * The product and feature decision, as one call.
     *
     * @return the result's own status when the product and feature are covered,
     *         {@link LicenseStatus#WRONG_PRODUCT} when they are not, and
     *         whatever earlier check failed otherwise
     */
    public LicenseStatus checkFeature(LicenseCheckResult result, String productId, String featureId) {
        if (result == null) {
            return LicenseStatus.MISSING;
        }
        if (result.payload() == null || !result.grantsAccess()) {
            return result.status();
        }
        if (!result.payload().coversProduct(productId)) {
            return LicenseStatus.WRONG_PRODUCT;
        }
        if (featureId != null && !result.payload().coversFeature(productId, featureId)) {
            return LicenseStatus.WRONG_PRODUCT;
        }
        return result.status();
    }

    // -------------------------------------------------------- payload reading

    private static LicensePayload readPayload(Map<String, Object> json) {
        Map<String, Object> binding = MiniJson.asObject(json.get("binding"));
        String bindingMode = "unbound";
        List<String> serverUuids = List.of();
        String boundDiscordUserId = null;

        if (binding != null) {
            bindingMode = MiniJson.asString(binding.get("mode"));
            serverUuids = MiniJson.asStringList(binding.get("server_uuids")).stream()
                    .map(uuid -> uuid.toLowerCase(Locale.ROOT))
                    .toList();
            boundDiscordUserId = MiniJson.asString(binding.get("discord_user_id"));
        }

        Map<String, List<String>> products = new LinkedHashMap<>();
        Map<String, Object> productsJson = MiniJson.asObject(json.get("products"));
        if (productsJson != null) {
            for (Map.Entry<String, Object> entry : productsJson.entrySet()) {
                products.put(entry.getKey(), MiniJson.asStringList(entry.getValue()));
            }
        }

        Long grace = MiniJson.asLong(json.get("grace_period_seconds"));
        Long generation = MiniJson.asLong(json.get("generation"));

        return new LicensePayload(
                MiniJson.asString(json.get("license_id")),
                MiniJson.asString(json.get("license_type")),
                MiniJson.asString(json.get("issuer")),
                bindingMode,
                serverUuids,
                boundDiscordUserId,
                products,
                instant(MiniJson.asString(json.get("issued_at"))),
                instant(MiniJson.asString(json.get("not_before"))),
                instant(MiniJson.asString(json.get("expires_at"))),
                grace == null ? 0L : grace,
                generation == null ? 1 : generation.intValue());
    }

    private static Instant instant(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Exception messages vary by JDK; never let a null one produce "null". */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
