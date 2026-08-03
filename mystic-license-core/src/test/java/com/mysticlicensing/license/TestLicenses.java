package com.mysticlicensing.license;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mints real, correctly signed {@code .mclicense} files for tests.
 *
 * <p>Everything here signs and encrypts for real, with a throwaway Ed25519
 * keypair generated per instance. Nothing is stubbed, so a test that asserts
 * {@code VALID} is asserting that the actual crypto path works end to end - and
 * a test that asserts {@code INVALID_SIGNATURE} is exercising the same code an
 * attacker would hit.
 *
 * <p>The published development keys from the portal are used only by
 * {@code InteropVectorTest}; every other test signs with keys generated here,
 * so no test depends on key material that could change.
 */
final class TestLicenses {

    static final String SERVER_UUID = "20bf6b33-b798-43bb-b248-e4162a26ce28";
    static final String OTHER_SERVER_UUID = "00000000-0000-4000-8000-000000000000";

    static final String SIGNING_KEY_ID = "test-signing-key";
    static final String CONTENT_KEY_ID = "test-content-key";

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final KeyPair signingKeys;
    private final byte[] contentKey;

    private TestLicenses(KeyPair signingKeys, byte[] contentKey) {
        this.signingKeys = signingKeys;
        this.contentKey = contentKey;
    }

    static TestLicenses create() {
        return new TestLicenses(newSigningKeys(), newContentKey());
    }

    static KeyPair newSigningKeys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 unavailable; needs Java 15+", e);
        }
    }

    static byte[] newContentKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    String publicKeySpkiBase64() {
        return Base64.getEncoder().encodeToString(signingKeys.getPublic().getEncoded());
    }

    String contentKeyBase64Url() {
        return base64Url(contentKey);
    }

    /** A verifier that trusts exactly this instance's keys. */
    McLicenseVerifier verifier() {
        return McLicenseVerifier.builder()
                .trustSigningKey(SIGNING_KEY_ID, publicKeySpkiBase64())
                .addContentKey(CONTENT_KEY_ID, contentKeyBase64Url())
                .build();
    }

    /** A license bound to {@link #SERVER_UUID}, valid, covering nothing yet. */
    Builder license() {
        return new Builder(this);
    }

    // ------------------------------------------------------------- minting

    static final class Builder {
        private final TestLicenses keys;

        // payload
        private String format = "mystic-license";
        private long formatVersion = 1;
        private String licenseId = "lic_01TESTTESTTESTTESTTESTTEST";
        private String licenseType = "patreon";
        private String issuer = "Mystic Licensing";
        private String bindingMode = "server_uuid";
        private List<String> serverUuids = new ArrayList<>(List.of(SERVER_UUID));
        private String discordUserId;
        private final Map<String, Object> products = new LinkedHashMap<>();
        private String issuedAt = "2026-07-27T18:00:00.000Z";
        private String notBefore = "2026-07-27T18:00:00.000Z";
        private String expiresAt = "2026-08-10T18:00:00.000Z";
        private long gracePeriodSeconds = 259_200L; // 3 days
        private long generation = 1;

        // envelope
        private String magic = "MCL1";
        private long envelopeVersion = 1;
        private String signingKeyId = SIGNING_KEY_ID;
        private String encryptionKeyId = CONTENT_KEY_ID;
        private String encryptionAlgorithm = "AES-256-GCM";
        private String signatureAlgorithm = "Ed25519";

        // deliberate breakage
        private byte[] encryptWith;
        private KeyPair signWith;
        private boolean corruptCiphertext;
        private boolean corruptIv;
        private boolean corruptTag;
        private boolean corruptSignature;

        private Builder(TestLicenses keys) {
            this.keys = keys;
        }

        Builder formatVersion(long value) {
            this.formatVersion = value;
            return this;
        }

        Builder format(String value) {
            this.format = value;
            return this;
        }

        Builder licenseId(String value) {
            this.licenseId = value;
            return this;
        }

        Builder unbound() {
            this.bindingMode = "unbound";
            this.serverUuids = new ArrayList<>();
            return this;
        }

        Builder boundToDiscordUser(String id) {
            this.bindingMode = "discord_user";
            this.serverUuids = new ArrayList<>();
            this.discordUserId = id;
            return this;
        }

        Builder boundTo(String... uuids) {
            this.bindingMode = "server_uuid";
            this.serverUuids = new ArrayList<>(Arrays.asList(uuids));
            return this;
        }

        Builder grants(String productId, String... featureIds) {
            products.put(productId, List.of(featureIds));
            return this;
        }

        Builder issuedAt(String value) {
            this.issuedAt = value;
            return this;
        }

        Builder notBefore(String value) {
            this.notBefore = value;
            return this;
        }

        /** Pass null for a non-expiring license. */
        Builder expiresAt(String value) {
            this.expiresAt = value;
            return this;
        }

        Builder gracePeriodSeconds(long value) {
            this.gracePeriodSeconds = value;
            return this;
        }

        Builder magic(String value) {
            this.magic = value;
            return this;
        }

        Builder envelopeVersion(long value) {
            this.envelopeVersion = value;
            return this;
        }

        Builder signingKeyId(String value) {
            this.signingKeyId = value;
            return this;
        }

        Builder encryptionKeyId(String value) {
            this.encryptionKeyId = value;
            return this;
        }

        Builder encryptionAlgorithm(String value) {
            this.encryptionAlgorithm = value;
            return this;
        }

        /** Encrypt with a key the verifier does not hold. */
        Builder encryptedWith(byte[] key) {
            this.encryptWith = key;
            return this;
        }

        /** Sign with a keypair the verifier does not trust. */
        Builder signedWith(KeyPair keyPair) {
            this.signWith = keyPair;
            return this;
        }

        /** Flip a ciphertext byte after signing. */
        Builder withFlippedCiphertext() {
            this.corruptCiphertext = true;
            return this;
        }

        Builder withFlippedIv() {
            this.corruptIv = true;
            return this;
        }

        Builder withFlippedTag() {
            this.corruptTag = true;
            return this;
        }

        Builder withFlippedSignature() {
            this.corruptSignature = true;
            return this;
        }

        String json() {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        byte[] bytes() {
            String payload = TestJson.write(payloadMap());

            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            byte[] key = encryptWith != null ? encryptWith : keys.contentKey;
            byte[] sealed = encrypt(key, iv, payload.getBytes(StandardCharsets.UTF_8));

            // GCM output is ciphertext || tag; the format stores them apart.
            int tagBytes = TAG_BITS / 8;
            byte[] ciphertext = Arrays.copyOfRange(sealed, 0, sealed.length - tagBytes);
            byte[] tag = Arrays.copyOfRange(sealed, sealed.length - tagBytes, sealed.length);

            String ivText = base64Url(iv);
            String ciphertextText = base64Url(ciphertext);
            String tagText = base64Url(tag);

            byte[] signingInput = SigningInput.build(
                    magic, envelopeVersion, signingKeyId, encryptionKeyId,
                    encryptionAlgorithm, signatureAlgorithm, ivText, ciphertextText, tagText);
            String signatureText = base64Url(
                    sign(signWith != null ? signWith : keys.signingKeys, signingInput));

            // Tamper only after signing, which is what an attacker can actually do.
            if (corruptCiphertext) {
                ciphertextText = flipFirstChar(ciphertextText);
            }
            if (corruptIv) {
                ivText = flipFirstChar(ivText);
            }
            if (corruptTag) {
                tagText = flipFirstChar(tagText);
            }
            if (corruptSignature) {
                signatureText = flipFirstChar(signatureText);
            }

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("magic", magic);
            envelope.put("version", envelopeVersion);
            envelope.put("signing_key_id", signingKeyId);
            envelope.put("encryption_key_id", encryptionKeyId);
            envelope.put("algorithm", Map.of(
                    "encryption", encryptionAlgorithm,
                    "signature", signatureAlgorithm));
            envelope.put("iv", ivText);
            envelope.put("ciphertext", ciphertextText);
            envelope.put("authentication_tag", tagText);
            envelope.put("signature", signatureText);

            return TestJson.write(envelope).getBytes(StandardCharsets.UTF_8);
        }

        private Map<String, Object> payloadMap() {
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("mode", bindingMode);
            if (!serverUuids.isEmpty()) {
                binding.put("server_uuids", List.copyOf(serverUuids));
            }
            if (discordUserId != null) {
                binding.put("discord_user_id", discordUserId);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("format", format);
            payload.put("format_version", formatVersion);
            payload.put("license_id", licenseId);
            payload.put("issuer", issuer);
            payload.put("license_type", licenseType);
            payload.put("subject", Map.of("discord_user_id", "123456789012345678"));
            payload.put("binding", binding);
            payload.put("products", products);
            payload.put("issued_at", issuedAt);
            payload.put("not_before", notBefore);
            payload.put("expires_at", expiresAt);
            payload.put("grace_period_seconds", gracePeriodSeconds);
            payload.put("generation", generation);
            payload.put("supersedes_license_id", null);
            return payload;
        }
    }

    // ------------------------------------------------------------------ util

    static Instant at(String isoInstant) {
        return Instant.parse(isoInstant);
    }

    private static byte[] encrypt(byte[] key, byte[] iv, byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("test encryption failed", e);
        }
    }

    private static byte[] sign(KeyPair keyPair, byte[] message) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(keyPair.getPrivate());
            signature.update(message);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("test signing failed", e);
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /**
     * Changes exactly one base64url character, so both the text and the decoded
     * bytes differ.
     *
     * <p>Deliberately the <em>first</em> character, not the last. In an unpadded
     * base64url string the final character can carry discarded low bits - for a
     * 64-byte signature (86 characters) the last character's bottom four bits
     * are thrown away entirely, so flipping {@code A} to {@code B} there would
     * change the text while decoding to identical bytes. A test written that
     * way passes for the wrong reason. The first character's six bits are
     * always significant.
     */
    static String flipFirstChar(String base64url) {
        char first = base64url.charAt(0);
        char replacement = first == 'A' ? 'B' : 'A';
        return replacement + base64url.substring(1);
    }
}
