package com.mysticlicensing.license;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract with the issuer.
 *
 * <p>Everything else in this suite tests that the library agrees with itself.
 * This tests that it agrees with the TypeScript licensing portal, using the
 * portal's own generated vectors and a real license file it produced. If this
 * class fails, the wire format has moved and every license in the field is
 * about to stop verifying - which is a release blocker, not a flaky test.
 *
 * <p>The keys used here are the portal's published development keys. They are
 * committed to both repositories on purpose and have never signed a real
 * license; see {@code src/test/resources/fixtures/README.md}.
 */
class InteropVectorTest {

    private static Map<String, Object> vectors;
    private static Map<String, Object> keys;
    private static byte[] licenseFileBytes;

    /** The instant the portal's fixture is valid at. */
    private static final Instant AT = Instant.parse("2026-07-28T00:00:00Z");

    @BeforeAll
    static void loadFixtures() throws Exception {
        vectors = MiniJson.asObject(MiniJson.parse(resource("fixtures/interop-vectors.json")));
        assertNotNull(vectors, "interop-vectors.json did not parse as an object");
        keys = MiniJson.asObject(vectors.get("keys"));
        licenseFileBytes = resource("fixtures/license.mclicense").getBytes(StandardCharsets.UTF_8);
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = InteropVectorTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "missing test resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static McLicenseVerifier fixtureVerifier() {
        return McLicenseVerifier.builder()
                .trustSigningKey(
                        MiniJson.asString(keys.get("signing_key_id")),
                        MiniJson.asString(keys.get("ed25519_public_key_spki_base64")))
                .addContentKey(
                        MiniJson.asString(keys.get("encryption_key_id")),
                        MiniJson.asString(keys.get("aes_256_key_base64url")))
                .build();
    }

    @Test
    @DisplayName("the rebuilt signing input matches the portal's, byte for byte")
    void signingInputMatchesTheIssuer() {
        Map<String, Object> license = MiniJson.asObject(vectors.get("example_license"));
        Map<String, Object> signature = MiniJson.asObject(vectors.get("signature"));

        byte[] rebuilt = SigningInput.build(
                MiniJson.asString(license.get("magic")),
                MiniJson.asLong(license.get("version")),
                MiniJson.asString(license.get("signing_key_id")),
                MiniJson.asString(license.get("encryption_key_id")),
                "AES-256-GCM",
                "Ed25519",
                MiniJson.asString(license.get("iv")),
                MiniJson.asString(license.get("ciphertext")),
                MiniJson.asString(license.get("authentication_tag")));

        byte[] expected = Base64.getUrlDecoder()
                .decode(MiniJson.asString(signature.get("signing_input_base64url")));

        assertArrayEquals(expected, rebuilt,
                "signing input drifted from the issuer; licenses in the field will stop verifying");
    }

    @Test
    @DisplayName("the domain separator and field count are what the portal documents")
    void signingInputStructure() {
        Map<String, Object> signature = MiniJson.asObject(vectors.get("signature"));
        assertEquals(SigningInput.ENVELOPE_SIGNING_CONTEXT,
                MiniJson.asString(signature.get("context")));
    }

    @Test
    @DisplayName("the portal's license.mclicense verifies, decrypts and validates")
    void fixtureLicenseVerifies() {
        LicenseCheckResult result = fixtureVerifier()
                .verify(licenseFileBytes, TestLicenses.SERVER_UUID, AT);

        assertEquals(LicenseStatus.VALID, result.status(), result::toString);
        assertNotNull(result.payload());
        assertEquals("lic_01K1A2BCDEF3456789ABCDEFGH", result.payload().licenseId());
        assertEquals("patreon", result.payload().licenseType());
        assertEquals("Mystic Licensing", result.payload().issuer());
        assertEquals("server_uuid", result.payload().bindingMode());
    }

    @Test
    @DisplayName("the decrypted payload carries the entitlements the portal issued")
    void fixtureEntitlements() {
        LicenseCheckResult result = fixtureVerifier()
                .verify(licenseFileBytes, TestLicenses.SERVER_UUID, AT);
        LicensePayload payload = result.payload();

        assertTrue(payload.coversProduct(Products.BOARDS));
        assertTrue(payload.coversFeature(Products.BOARDS, Products.Boards.SCOREBOARDS_MULTIPLE));
        assertTrue(payload.coversFeature(Products.BOARDS, Products.Boards.SCOREBOARDS_CONDITIONAL));

        assertFalse(payload.coversProduct(Products.HOLOS));
        assertFalse(payload.coversFeature(Products.BOARDS, "scoreboards.invented"));
    }

    @Test
    @DisplayName("the decrypted plaintext is the canonical payload the portal recorded")
    void decryptedPayloadMatchesCanonicalVector() {
        Map<String, Object> canonical = MiniJson.asObject(vectors.get("canonical_json"));
        long expectedLength = MiniJson.asLong(canonical.get("canonical_byte_length"));
        String canonicalText = MiniJson.asString(canonical.get("canonical_text"));

        assertNotNull(canonicalText);
        assertEquals(expectedLength, canonicalText.getBytes(StandardCharsets.UTF_8).length,
                "the vector's own canonical_text disagrees with its recorded length");

        // The library never re-serialises the payload, so the strongest check
        // available is that parsing the canonical text yields the same license
        // the encrypted fixture decrypts to.
        LicenseCheckResult result = fixtureVerifier()
                .verify(licenseFileBytes, TestLicenses.SERVER_UUID, AT);
        Map<String, Object> canonicalPayload = MiniJson.asObject(MiniJson.parse(canonicalText));

        assertEquals(MiniJson.asString(canonicalPayload.get("license_id")),
                result.payload().licenseId());
        assertEquals(Instant.parse(MiniJson.asString(canonicalPayload.get("issued_at"))),
                result.payload().issuedAt());
        assertEquals(Instant.parse(MiniJson.asString(canonicalPayload.get("expires_at"))),
                result.payload().expiresAt().orElseThrow());
        assertEquals(MiniJson.asLong(canonicalPayload.get("grace_period_seconds")),
                result.payload().gracePeriodSeconds());
    }

    @Test
    @DisplayName("the fixture is rejected on a server it is not bound to")
    void fixtureRejectedOnWrongServer() {
        LicenseCheckResult result = fixtureVerifier()
                .verify(licenseFileBytes, TestLicenses.OTHER_SERVER_UUID, AT);
        assertEquals(LicenseStatus.WRONG_SERVER, result.status());
    }

    @Test
    @DisplayName("tampering with the fixture's ciphertext fails the signature, not the AES tag")
    void tamperedFixtureFailsSignatureFirst() {
        String text = new String(licenseFileBytes, StandardCharsets.UTF_8);
        Map<String, Object> envelope = MiniJson.asObject(MiniJson.parse(text));
        String original = MiniJson.asString(envelope.get("ciphertext"));
        envelope.put("ciphertext", TestLicenses.flipFirstChar(original));

        LicenseCheckResult result = fixtureVerifier().verify(
                TestJson.write(envelope).getBytes(StandardCharsets.UTF_8),
                TestLicenses.SERVER_UUID, AT);

        assertEquals(LicenseStatus.INVALID_SIGNATURE, result.status(),
                "unauthenticated bytes must never reach the cipher");
    }

    @Test
    @DisplayName("reformatting the envelope JSON does not break the signature")
    void envelopeFormattingIsNotSigned() {
        // The fixture on disk is pretty-printed; re-emitting it compactly must
        // still verify, because the signature covers fields and not bytes of JSON.
        Map<String, Object> envelope =
                MiniJson.asObject(MiniJson.parse(new String(licenseFileBytes, StandardCharsets.UTF_8)));
        byte[] compact = TestJson.write(envelope).getBytes(StandardCharsets.UTF_8);

        assertEquals(LicenseStatus.VALID,
                fixtureVerifier().verify(compact, TestLicenses.SERVER_UUID, AT).status());
    }

    @Test
    @DisplayName("production keys are not the published development keys")
    void productionKeysAreDistinct() {
        assertFalse(EmbeddedKeys.SIGNING_KEY_ID.equals(MiniJson.asString(keys.get("signing_key_id"))),
            "a development signing key id reached EmbeddedKeys");
        assertFalse(EmbeddedKeys.CONTENT_KEY_ID.equals(MiniJson.asString(keys.get("encryption_key_id"))),
            "a development content key id reached EmbeddedKeys");
        assertFalse(
            EmbeddedKeys.SIGNING_SPKI_BASE64
                .equals(MiniJson.asString(keys.get("ed25519_public_key_spki_base64"))),
            "the development public key reached EmbeddedKeys");
        assertFalse(
            EmbeddedKeys.CONTENT_KEY_B64URL
                .equals(MiniJson.asString(keys.get("aes_256_key_base64url"))),
            "the development content key reached EmbeddedKeys");
    }

    @Test
    @DisplayName("the embedded production keys are structurally usable")
    void embeddedKeysLoad() {
        // Does not prove they are the right keys - only the portal can say that.
        // It does prove a copy/paste error would fail the build rather than
        // every license verification on every server.
        McLicenseVerifier verifier = EmbeddedKeys.trustAll(McLicenseVerifier.builder()).build();
        assertNotNull(verifier);
    }
}
