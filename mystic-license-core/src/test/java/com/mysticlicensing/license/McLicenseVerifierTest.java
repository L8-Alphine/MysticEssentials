package com.mysticlicensing.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status matrix. Every license here is genuinely signed and encrypted by
 * {@link TestLicenses}, so a passing assertion means the real crypto path ran.
 */
class McLicenseVerifierTest {

    private final TestLicenses licenses = TestLicenses.create();
    private final McLicenseVerifier verifier = licenses.verifier();

    /** Comfortably inside the fixture licenses' validity window. */
    private static final Instant DURING = Instant.parse("2026-07-28T00:00:00Z");

    private LicenseCheckResult verify(byte[] bytes) {
        return verifier.verify(bytes, TestLicenses.SERVER_UUID, DURING);
    }

    private LicenseCheckResult verifyAt(byte[] bytes, String isoInstant) {
        return verifier.verify(bytes, TestLicenses.SERVER_UUID, Instant.parse(isoInstant));
    }

    // ------------------------------------------------------------- happy path

    @Test
    @DisplayName("a well-formed license for this server is VALID")
    void validLicense() {
        LicenseCheckResult result = verify(licenses.license()
                .grants(Products.ESSENTIALS, Products.Essentials.MODULE_CUSTOM_CONTENT)
                .bytes());

        assertEquals(LicenseStatus.VALID, result.status(), result::toString);
        assertTrue(result.grantsAccess());
        assertEquals("lic_01TESTTESTTESTTESTTESTTEST", result.payload().licenseId());
    }

    // -------------------------------------------------------------- validity

    @Nested
    @DisplayName("time")
    class TimeChecks {

        @Test
        @DisplayName("expired but inside the grace period is GRACE_PERIOD")
        void withinGracePeriod() {
            byte[] license = licenses.license()
                    .expiresAt("2026-08-10T18:00:00.000Z")
                    .gracePeriodSeconds(259_200L)
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            // One day past expiry, two days of grace still to run.
            LicenseCheckResult result = verifyAt(license, "2026-08-11T18:00:00Z");

            assertEquals(LicenseStatus.GRACE_PERIOD, result.status(), result::toString);
            assertTrue(result.grantsAccess(), "grace period must still grant access");
            assertNotNull(result.detail(), "the operator needs to be told when grace ends");
        }

        @Test
        @DisplayName("past expiry and past grace is EXPIRED")
        void pastGracePeriod() {
            byte[] license = licenses.license()
                    .expiresAt("2026-08-10T18:00:00.000Z")
                    .gracePeriodSeconds(259_200L)
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            LicenseCheckResult result = verifyAt(license, "2026-08-14T18:00:01Z");

            assertEquals(LicenseStatus.EXPIRED, result.status());
            assertFalse(result.grantsAccess());
            assertNotNull(result.payload(),
                    "an expired license still has an id worth logging");
        }

        @Test
        @DisplayName("the exact end of the grace period still grants access")
        void graceBoundaryIsInclusive() {
            byte[] license = licenses.license()
                    .expiresAt("2026-08-10T18:00:00.000Z")
                    .gracePeriodSeconds(60L)
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            assertEquals(LicenseStatus.GRACE_PERIOD,
                    verifyAt(license, "2026-08-10T18:01:00Z").status());
            assertEquals(LicenseStatus.EXPIRED,
                    verifyAt(license, "2026-08-10T18:01:01Z").status());
        }

        @Test
        @DisplayName("a zero-length grace period expires the moment the license does")
        void noGracePeriod() {
            byte[] license = licenses.license()
                    .expiresAt("2026-08-10T18:00:00.000Z")
                    .gracePeriodSeconds(0L)
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            assertEquals(LicenseStatus.VALID, verifyAt(license, "2026-08-10T18:00:00Z").status());
            assertEquals(LicenseStatus.EXPIRED, verifyAt(license, "2026-08-10T18:00:01Z").status());
        }

        @Test
        @DisplayName("before not_before is NOT_YET_VALID")
        void notYetValid() {
            byte[] license = licenses.license()
                    .notBefore("2026-09-01T00:00:00.000Z")
                    .expiresAt("2026-10-01T00:00:00.000Z")
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            LicenseCheckResult result = verifyAt(license, "2026-08-01T00:00:00Z");

            assertEquals(LicenseStatus.NOT_YET_VALID, result.status());
            assertFalse(result.grantsAccess());
        }

        @Test
        @DisplayName("a null expires_at never expires")
        void nonExpiringLicense() {
            byte[] license = licenses.license()
                    .expiresAt(null)
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            assertEquals(LicenseStatus.VALID, verifyAt(license, "2026-08-01T00:00:00Z").status());
            assertEquals(LicenseStatus.VALID, verifyAt(license, "2099-01-01T00:00:00Z").status());
            assertTrue(verify(license).payload().expiresAt().isEmpty());
        }
    }

    // ------------------------------------------------------------- binding

    @Nested
    @DisplayName("server binding")
    class BindingChecks {

        @Test
        @DisplayName("a license for another server is WRONG_SERVER")
        void wrongServer() {
            byte[] license = licenses.license()
                    .boundTo(TestLicenses.OTHER_SERVER_UUID)
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            LicenseCheckResult result = verify(license);

            assertEquals(LicenseStatus.WRONG_SERVER, result.status());
            assertFalse(result.grantsAccess());
        }

        @ParameterizedTest(name = "server uuid \"{0}\" is accepted")
        @ValueSource(strings = {
                "20bf6b33-b798-43bb-b248-e4162a26ce28",
                "20BF6B33-B798-43BB-B248-E4162A26CE28",
                "20Bf6B33-b798-43Bb-B248-e4162A26cE28"
        })
        @DisplayName("the server uuid comparison ignores case")
        void uuidComparisonIsCaseInsensitive(String uuid) {
            byte[] license = licenses.license()
                    .boundTo("20bf6b33-b798-43bb-b248-e4162a26ce28")
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            assertEquals(LicenseStatus.VALID, verifier.verify(license, uuid, DURING).status());
        }

        @Test
        @DisplayName("an uppercase binding in the payload still matches")
        void uppercaseBindingInPayload() {
            byte[] license = licenses.license()
                    .boundTo("20BF6B33-B798-43BB-B248-E4162A26CE28")
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            assertEquals(LicenseStatus.VALID,
                    verifier.verify(license, TestLicenses.SERVER_UUID, DURING).status());
        }

        @Test
        @DisplayName("a multi-server license matches any of its uuids")
        void multipleBoundServers() {
            byte[] license = licenses.license()
                    .boundTo(TestLicenses.OTHER_SERVER_UUID, TestLicenses.SERVER_UUID)
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            assertEquals(LicenseStatus.VALID, verify(license).status());
        }

        @Test
        @DisplayName("an unbound license is valid anywhere")
        void unboundLicense() {
            byte[] license = licenses.license().unbound().grants(Products.ESSENTIALS, "*").bytes();

            assertEquals(LicenseStatus.VALID,
                    verifier.verify(license, "11111111-2222-3333-4444-555555555555", DURING).status());
        }

        @Test
        @DisplayName("a discord_user license passes, because a mod cannot know the operator's id")
        void discordUserBinding() {
            byte[] license = licenses.license()
                    .boundToDiscordUser("123456789012345678")
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            LicenseCheckResult result = verify(license);

            assertEquals(LicenseStatus.VALID, result.status());
            assertEquals("123456789012345678", result.payload().boundDiscordUserId().orElseThrow());
        }

        @Test
        @DisplayName("a null server uuid skips the binding check rather than failing")
        void nullServerUuidSkipsBinding() {
            byte[] license = licenses.license()
                    .boundTo(TestLicenses.OTHER_SERVER_UUID)
                    .grants(Products.ESSENTIALS, "*")
                    .bytes();

            assertEquals(LicenseStatus.VALID, verifier.verify(license, null, DURING).status());
        }
    }

    // ------------------------------------------------------- products/features

    @Nested
    @DisplayName("products and features")
    class EntitlementChecks {

        @Test
        @DisplayName("an explicitly granted feature is covered")
        void explicitGrant() {
            LicenseCheckResult result = verify(licenses.license()
                    .grants(Products.ESSENTIALS, Products.Essentials.MODULE_CUSTOM_CONTENT)
                    .bytes());

            assertEquals(LicenseStatus.VALID,
                    verifier.checkFeature(result, Products.ESSENTIALS,
                            Products.Essentials.MODULE_CUSTOM_CONTENT));
            assertTrue(result.payload().coversFeature(Products.ESSENTIALS,
                    Products.Essentials.MODULE_CUSTOM_CONTENT));
        }

        @Test
        @DisplayName("an unlisted feature of a licensed product is WRONG_PRODUCT")
        void unlistedFeature() {
            LicenseCheckResult result = verify(licenses.license()
                    .grants(Products.ESSENTIALS, Products.Essentials.EDITOR_KIT)
                    .bytes());

            assertEquals(LicenseStatus.WRONG_PRODUCT,
                    verifier.checkFeature(result, Products.ESSENTIALS,
                            Products.Essentials.MODULE_CUSTOM_CONTENT));
            assertFalse(result.payload().coversFeature(Products.ESSENTIALS,
                    Products.Essentials.MODULE_CUSTOM_CONTENT));
        }

        @Test
        @DisplayName("an unlisted product is WRONG_PRODUCT")
        void unlistedProduct() {
            LicenseCheckResult result = verify(licenses.license()
                    .grants(Products.BOARDS, "*")
                    .bytes());

            assertEquals(LicenseStatus.WRONG_PRODUCT,
                    verifier.checkFeature(result, Products.ESSENTIALS, null));
            assertFalse(result.payload().coversProduct(Products.ESSENTIALS));
        }

        @Test
        @DisplayName("a per-product feature wildcard covers every feature of that product")
        void featureWildcard() {
            LicensePayload payload = verify(licenses.license()
                    .grants(Products.ESSENTIALS, "*")
                    .bytes()).payload();

            assertTrue(payload.coversProduct(Products.ESSENTIALS));
            for (String feature : Products.Essentials.ALL) {
                assertTrue(payload.coversFeature(Products.ESSENTIALS, feature), feature);
            }
            assertFalse(payload.coversProduct(Products.GUILDS),
                    "a feature wildcard must not leak across products");
        }

        @Test
        @DisplayName("a global license covers every product and feature")
        void globalWildcard() {
            LicensePayload payload = verify(licenses.license()
                    .grants("*", "*")
                    .bytes()).payload();

            assertTrue(payload.coversProduct(Products.ESSENTIALS));
            assertTrue(payload.coversProduct(Products.GUILDS));
            assertTrue(payload.coversProduct("a-product-that-does-not-exist-yet"));
            assertTrue(payload.coversFeature(Products.ESSENTIALS,
                    Products.Essentials.MODULE_CUSTOM_CONTENT));
            assertTrue(payload.coversFeature(Products.GUILDS, Products.Guilds.MODULE_NPC_GUARDS));
        }

        @Test
        @DisplayName("a product wildcard with named features grants only those features")
        void productWildcardWithNamedFeatures() {
            LicensePayload payload = verify(licenses.license()
                    .grants("*", Products.Essentials.MODULE_CUSTOM_CONTENT)
                    .bytes()).payload();

            assertTrue(payload.coversProduct(Products.GUILDS));
            assertTrue(payload.coversFeature(Products.GUILDS,
                    Products.Essentials.MODULE_CUSTOM_CONTENT));
            assertFalse(payload.coversFeature(Products.GUILDS, Products.Guilds.MODULE_NPC_GUARDS));
        }

        @Test
        @DisplayName("a product listed with no features covers the product but no feature")
        void emptyFeatureList() {
            LicensePayload payload = verify(licenses.license()
                    .grants(Products.ESSENTIALS)
                    .bytes()).payload();

            assertTrue(payload.coversProduct(Products.ESSENTIALS));
            assertFalse(payload.coversFeature(Products.ESSENTIALS,
                    Products.Essentials.MODULE_CUSTOM_CONTENT));
        }

        @Test
        @DisplayName("checkFeature reports the earlier failure, not WRONG_PRODUCT")
        void earlierFailureWins() {
            LicenseCheckResult expired = verifyAt(licenses.license()
                    .expiresAt("2026-08-10T18:00:00.000Z")
                    .gracePeriodSeconds(0L)
                    .grants(Products.ESSENTIALS, "*")
                    .bytes(), "2027-01-01T00:00:00Z");

            assertEquals(LicenseStatus.EXPIRED,
                    verifier.checkFeature(expired, Products.ESSENTIALS, "*"));
        }
    }

    // ------------------------------------------------------------- tampering

    @Nested
    @DisplayName("tampering")
    class TamperChecks {

        @Test
        @DisplayName("a flipped ciphertext byte is INVALID_SIGNATURE, not DECRYPTION_FAILED")
        void flippedCiphertext() {
            LicenseCheckResult result = verify(licenses.license()
                    .grants(Products.ESSENTIALS, "*")
                    .withFlippedCiphertext()
                    .bytes());

            assertEquals(LicenseStatus.INVALID_SIGNATURE, result.status(),
                    "the signature covers the ciphertext, so it must fail first");
        }

        @Test
        @DisplayName("a modified iv is INVALID_SIGNATURE")
        void flippedIv() {
            assertEquals(LicenseStatus.INVALID_SIGNATURE, verify(licenses.license()
                    .grants(Products.ESSENTIALS, "*")
                    .withFlippedIv()
                    .bytes()).status());
        }

        @Test
        @DisplayName("a modified authentication tag is INVALID_SIGNATURE")
        void flippedTag() {
            assertEquals(LicenseStatus.INVALID_SIGNATURE, verify(licenses.license()
                    .grants(Products.ESSENTIALS, "*")
                    .withFlippedTag()
                    .bytes()).status());
        }

        @Test
        @DisplayName("a modified signature is INVALID_SIGNATURE")
        void flippedSignature() {
            assertEquals(LicenseStatus.INVALID_SIGNATURE, verify(licenses.license()
                    .grants(Products.ESSENTIALS, "*")
                    .withFlippedSignature()
                    .bytes()).status());
        }

        @Test
        @DisplayName("a signature from a different keypair is INVALID_SIGNATURE")
        void foreignKeypair() {
            KeyPair attacker = TestLicenses.newSigningKeys();

            LicenseCheckResult result = verify(licenses.license()
                    .grants("*", "*")
                    .signedWith(attacker)
                    .bytes());

            assertEquals(LicenseStatus.INVALID_SIGNATURE, result.status(),
                    "forging a license must require the portal's private key");
            assertNull(result.payload(), "a forged license must not yield a payload");
        }

        @Test
        @DisplayName("a license encrypted with a different AES key is DECRYPTION_FAILED")
        void wrongContentKey() {
            // Correctly signed, so it gets past the signature - then the tag fails.
            LicenseCheckResult result = verify(licenses.license()
                    .grants(Products.ESSENTIALS, "*")
                    .encryptedWith(TestLicenses.newContentKey())
                    .bytes());

            assertEquals(LicenseStatus.DECRYPTION_FAILED, result.status());
        }
    }

    // ------------------------------------------------------------------ keys

    @Nested
    @DisplayName("key resolution")
    class KeyChecks {

        @Test
        @DisplayName("an untrusted signing_key_id is UNKNOWN_SIGNING_KEY")
        void unknownSigningKey() {
            LicenseCheckResult result = verify(licenses.license()
                    .signingKeyId("mystic-signing-2099-99")
                    .grants(Products.ESSENTIALS, "*")
                    .bytes());

            assertEquals(LicenseStatus.UNKNOWN_SIGNING_KEY, result.status());
            assertTrue(result.detail().contains("mystic-signing-2099-99"),
                    "the operator needs to see which key id was rejected");
        }

        @Test
        @DisplayName("an unknown encryption_key_id is UNKNOWN_ENCRYPTION_KEY")
        void unknownEncryptionKey() {
            LicenseCheckResult result = verify(licenses.license()
                    .encryptionKeyId("mystic-license-content-v99")
                    .grants(Products.ESSENTIALS, "*")
                    .bytes());

            assertEquals(LicenseStatus.UNKNOWN_ENCRYPTION_KEY, result.status());
        }

        @Test
        @DisplayName("the content key is never requested when the signature fails")
        void contentKeyNotRequestedOnBadSignature() {
            AtomicInteger requests = new AtomicInteger();
            McLicenseVerifier watched = McLicenseVerifier.builder()
                    .trustSigningKey(TestLicenses.SIGNING_KEY_ID, licenses.publicKeySpkiBase64())
                    .contentKeySource(keyId -> {
                        requests.incrementAndGet();
                        return null;
                    })
                    .build();

            LicenseCheckResult result = watched.verify(licenses.license()
                    .grants(Products.ESSENTIALS, "*")
                    .withFlippedCiphertext()
                    .bytes(), TestLicenses.SERVER_UUID, DURING);

            assertEquals(LicenseStatus.INVALID_SIGNATURE, result.status());
            assertEquals(0, requests.get(),
                    "the content key must not be touched until the envelope is authentic");
        }

        @Test
        @DisplayName("the content key is requested once the signature verifies")
        void contentKeyRequestedOnGoodSignature() {
            AtomicInteger requests = new AtomicInteger();
            McLicenseVerifier watched = McLicenseVerifier.builder()
                    .trustSigningKey(TestLicenses.SIGNING_KEY_ID, licenses.publicKeySpkiBase64())
                    .contentKeySource(keyId -> {
                        requests.incrementAndGet();
                        return null;
                    })
                    .build();

            watched.verify(licenses.license().grants(Products.ESSENTIALS, "*").bytes(),
                    TestLicenses.SERVER_UUID, DURING);

            assertEquals(1, requests.get(),
                    "this is the control for contentKeyNotRequestedOnBadSignature");
        }

        @Test
        @DisplayName("a throwing content key source does not escape as an exception")
        void throwingContentKeySource() {
            McLicenseVerifier watched = McLicenseVerifier.builder()
                    .trustSigningKey(TestLicenses.SIGNING_KEY_ID, licenses.publicKeySpkiBase64())
                    .contentKeySource(keyId -> {
                        throw new IllegalStateException("key store exploded");
                    })
                    .build();

            LicenseCheckResult result = assertDoesNotThrow(() -> watched.verify(
                    licenses.license().grants(Products.ESSENTIALS, "*").bytes(),
                    TestLicenses.SERVER_UUID, DURING));

            assertEquals(LicenseStatus.UNKNOWN_ENCRYPTION_KEY, result.status());
        }

        @Test
        @DisplayName("a verifier with no trusted signing key is a build error, not a runtime one")
        void builderRequiresASigningKey() {
            assertThrows(IllegalStateException.class, () -> McLicenseVerifier.builder().build());
        }

        @Test
        @DisplayName("a malformed public key is rejected at build time")
        void malformedPublicKey() {
            assertThrows(IllegalArgumentException.class, () ->
                    McLicenseVerifier.builder().trustSigningKey("k", "not-a-key"));
        }

        @Test
        @DisplayName("a content key of the wrong length is rejected at build time")
        void malformedContentKey() {
            assertThrows(IllegalArgumentException.class, () ->
                    McLicenseVerifier.builder().addContentKey("k", "AAAA"));
        }

        @Test
        @DisplayName("a PEM-wrapped public key is accepted as well as bare base64")
        void pemPublicKeyAccepted() {
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + licenses.publicKeySpkiBase64() + "\n"
                    + "-----END PUBLIC KEY-----\n";

            McLicenseVerifier fromPem = McLicenseVerifier.builder()
                    .trustSigningKey(TestLicenses.SIGNING_KEY_ID, pem)
                    .addContentKey(TestLicenses.CONTENT_KEY_ID, licenses.contentKeyBase64Url())
                    .build();

            assertEquals(LicenseStatus.VALID, fromPem.verify(
                    licenses.license().grants(Products.ESSENTIALS, "*").bytes(),
                    TestLicenses.SERVER_UUID, DURING).status());
        }
    }

    // ----------------------------------------------------------- malformation

    @Nested
    @DisplayName("malformed input")
    class MalformedChecks {

        @Test
        @DisplayName("a wrong magic is INVALID_FORMAT")
        void badMagic() {
            assertEquals(LicenseStatus.INVALID_FORMAT,
                    verify(licenses.license().magic("MCL9").bytes()).status());
        }

        @Test
        @DisplayName("a newer envelope version is UNSUPPORTED_VERSION")
        void unsupportedEnvelopeVersion() {
            assertEquals(LicenseStatus.UNSUPPORTED_VERSION,
                    verify(licenses.license().envelopeVersion(2).bytes()).status());
        }

        @Test
        @DisplayName("a newer payload version is UNSUPPORTED_VERSION")
        void unsupportedPayloadVersion() {
            assertEquals(LicenseStatus.UNSUPPORTED_VERSION,
                    verify(licenses.license().formatVersion(2).bytes()).status());
        }

        @Test
        @DisplayName("a payload that is not a mystic-license is INVALID_FORMAT")
        void wrongPayloadFormat() {
            assertEquals(LicenseStatus.INVALID_FORMAT,
                    verify(licenses.license().format("something-else").bytes()).status());
        }

        @Test
        @DisplayName("an unsupported algorithm is INVALID_FORMAT")
        void unsupportedAlgorithm() {
            assertEquals(LicenseStatus.INVALID_FORMAT,
                    verify(licenses.license().encryptionAlgorithm("AES-128-CBC").bytes()).status());
        }

        @ParameterizedTest(name = "\"{0}\" is rejected without throwing")
        @ValueSource(strings = {
                "",
                "   ",
                "not json at all",
                "{",
                "[]",
                "null",
                "42",
                "{\"magic\":\"MCL1\"}",
                "{\"magic\":\"MCL1\",\"version\":1,\"algorithm\":{}}"
        })
        @DisplayName("junk input never throws")
        void junkInput(String text) {
            LicenseCheckResult result = assertDoesNotThrow(() ->
                    verify(text.getBytes(StandardCharsets.UTF_8)));

            assertFalse(result.grantsAccess());
            assertNotNull(result.status());
        }

        @Test
        @DisplayName("a truncated license is rejected without throwing")
        void truncatedLicense() {
            byte[] full = licenses.license().grants(Products.ESSENTIALS, "*").bytes();
            byte[] half = new byte[full.length / 2];
            System.arraycopy(full, 0, half, 0, half.length);

            LicenseCheckResult result = assertDoesNotThrow(() -> verify(half));

            assertEquals(LicenseStatus.INVALID_FORMAT, result.status());
        }

        @Test
        @DisplayName("null bytes are rejected without throwing")
        void nullBytes() {
            assertEquals(LicenseStatus.INVALID_FORMAT,
                    assertDoesNotThrow(() -> verify(null)).status());
        }

        @Test
        @DisplayName("deeply nested JSON is rejected instead of overflowing the stack")
        void deeplyNestedJson() {
            String bomb = "[".repeat(5000) + "]".repeat(5000);

            LicenseCheckResult result = assertDoesNotThrow(() ->
                    verify(bomb.getBytes(StandardCharsets.UTF_8)));

            assertEquals(LicenseStatus.INVALID_FORMAT, result.status());
        }
    }

    // ------------------------------------------------------------------ files

    @Nested
    @DisplayName("file handling")
    class FileChecks {

        @Test
        @DisplayName("a missing file is MISSING, not an error")
        void missingFile(@TempDir Path dir) {
            LicenseCheckResult result = verifier.verifyFile(
                    dir.resolve("license.mclicense"), TestLicenses.SERVER_UUID, DURING);

            assertEquals(LicenseStatus.MISSING, result.status());
        }

        @Test
        @DisplayName("a null path is MISSING")
        void nullPath() {
            assertEquals(LicenseStatus.MISSING,
                    verifier.verifyFile(null, TestLicenses.SERVER_UUID, DURING).status());
        }

        @Test
        @DisplayName("a directory where the license should be is MISSING")
        void directoryInsteadOfFile(@TempDir Path dir) throws Exception {
            Path path = dir.resolve("license.mclicense");
            Files.createDirectory(path);

            assertEquals(LicenseStatus.MISSING,
                    verifier.verifyFile(path, TestLicenses.SERVER_UUID, DURING).status());
        }

        @Test
        @DisplayName("an empty file is INVALID_FORMAT")
        void emptyFile(@TempDir Path dir) throws Exception {
            Path path = Files.createFile(dir.resolve("license.mclicense"));

            assertEquals(LicenseStatus.INVALID_FORMAT,
                    verifier.verifyFile(path, TestLicenses.SERVER_UUID, DURING).status());
        }

        @Test
        @DisplayName("an implausibly large file is refused before it is read")
        void oversizedFile(@TempDir Path dir) throws Exception {
            Path path = dir.resolve("license.mclicense");
            byte[] filler = new byte[(int) McLicenseVerifier.MAX_FILE_BYTES + 1];
            Files.write(path, filler);

            LicenseCheckResult result = verifier.verifyFile(path, TestLicenses.SERVER_UUID, DURING);

            assertEquals(LicenseStatus.INVALID_FORMAT, result.status());
            assertTrue(result.detail().contains("implausibly large"));
        }

        @Test
        @DisplayName("a real license read from disk verifies")
        void licenseFromDisk(@TempDir Path dir) throws Exception {
            Path path = dir.resolve("license.mclicense");
            Files.write(path, licenses.license()
                    .grants(Products.ESSENTIALS, Products.Essentials.MODULE_CUSTOM_CONTENT)
                    .bytes());

            LicenseCheckResult result =
                    verifier.verifyFile(path, TestLicenses.SERVER_UUID, DURING);

            assertEquals(LicenseStatus.VALID, result.status(), result::toString);
        }

        @Test
        @DisplayName("a file of random bytes is rejected without throwing")
        void binaryGarbage(@TempDir Path dir) throws Exception {
            Path path = dir.resolve("license.mclicense");
            byte[] noise = new byte[512];
            new java.util.Random(1234).nextBytes(noise);
            Files.write(path, noise);

            LicenseCheckResult result = assertDoesNotThrow(() ->
                    verifier.verifyFile(path, TestLicenses.SERVER_UUID, DURING));

            assertFalse(result.grantsAccess());
        }
    }

    // ------------------------------------------------------------------ misc

    @Test
    @DisplayName("checkFeature tolerates a null result")
    void checkFeatureWithNullResult() {
        assertEquals(LicenseStatus.MISSING, verifier.checkFeature(null, Products.ESSENTIALS, "x"));
    }

    @Test
    @DisplayName("a null clock falls back to the system clock rather than failing")
    void nullClockUsesNow() {
        byte[] license = licenses.license()
                .notBefore("2020-01-01T00:00:00.000Z")
                .expiresAt(null)
                .grants(Products.ESSENTIALS, "*")
                .bytes();

        assertEquals(LicenseStatus.VALID,
                verifier.verify(license, TestLicenses.SERVER_UUID, null).status());
    }

    @Test
    @DisplayName("the payload's collections cannot be mutated by a caller")
    void payloadIsImmutable() {
        LicensePayload payload = verify(licenses.license()
                .grants(Products.ESSENTIALS, Products.Essentials.MODULE_CUSTOM_CONTENT)
                .bytes()).payload();

        assertThrowsUnsupported(() -> payload.products().put("x", java.util.List.of()));
        assertThrowsUnsupported(() -> payload.products().get(Products.ESSENTIALS).add("x"));
        assertThrowsUnsupported(() -> payload.serverUuids().add("x"));
    }

    private static void assertThrowsUnsupported(Runnable action) {
        try {
            action.run();
            org.junit.jupiter.api.Assertions.fail("expected the collection to be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // as intended
        }
    }
}
