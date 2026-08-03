package com.mysticlicensing.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure policy, which is the part that decides whether a billing question
 * becomes an outage.
 *
 * <p>The rule these tests exist to hold: a licensing problem switches off a
 * licensed feature and does nothing else. No exception ever leaves this class,
 * in any state, from any method.
 */
class LicenseGateTest {

    private static final Instant DURING = Instant.parse("2026-07-28T00:00:00Z");

    private final TestLicenses licenses = TestLicenses.create();

    /** Records what the gate logged, so the "one line at startup" rule is testable. */
    private static final class RecordingLog implements LicenseLog {
        final List<String> info = new ArrayList<>();
        final List<String> warn = new ArrayList<>();

        @Override
        public void info(String message) {
            info.add(message);
        }

        @Override
        public void warn(String message) {
            warn.add(message);
        }

        List<String> all() {
            List<String> out = new ArrayList<>(info);
            out.addAll(warn);
            return out;
        }
    }

    private LicenseGate.Builder gate(Path dir, RecordingLog log) {
        return LicenseGate.builder(Products.ESSENTIALS)
                .dataDir(dir)
                .withoutEmbeddedKeys()
                .trustSigningKey(TestLicenses.SIGNING_KEY_ID, licenses.publicKeySpkiBase64())
                .addContentKey(TestLicenses.CONTENT_KEY_ID, licenses.contentKeyBase64Url())
                .serverUuid(() -> TestLicenses.SERVER_UUID)
                .clock(Clock.fixed(DURING, ZoneOffset.UTC))
                .writeRequestFile(false)
                .logger(log);
    }

    private void writeLicense(Path dir, byte[] bytes) throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve(LicenseGate.LICENSE_FILE), bytes);
    }

    // --------------------------------------------------------------- basics

    @Test
    @DisplayName("a valid license unlocks its feature")
    void validLicenseUnlocksFeature(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license()
                .grants(Products.ESSENTIALS, Products.Essentials.MODULE_CUSTOM_CONTENT)
                .bytes());
        RecordingLog log = new RecordingLog();
        LicenseGate license = gate(dir, log).build();

        assertEquals(LicenseStatus.VALID, license.start());
        assertTrue(license.isValid());
        assertTrue(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));
        assertTrue(license.isProductLicensed(Products.ESSENTIALS));
        assertEquals("lic_01TESTTESTTESTTESTTESTTEST", license.licenseId().orElseThrow());
        assertTrue(license.expiresAt().isPresent());
    }

    @Test
    @DisplayName("a license that omits the feature leaves it locked")
    void unlicensedFeatureStaysLocked(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license()
                .grants(Products.ESSENTIALS, Products.Essentials.EDITOR_KIT)
                .bytes());
        LicenseGate license = gate(dir, new RecordingLog()).build();
        license.start();

        assertTrue(license.isValid(), "the license itself is fine");
        assertTrue(license.isProductLicensed(Products.ESSENTIALS));
        assertFalse(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));
    }

    @Test
    @DisplayName("no license file means MISSING, and the mod carries on")
    void missingLicense(@TempDir Path dir) {
        RecordingLog log = new RecordingLog();
        LicenseGate license = gate(dir, log).build();

        assertEquals(LicenseStatus.MISSING, assertDoesNotThrow(license::start));
        assertFalse(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));
        assertTrue(log.warn.stream().anyMatch(line -> line.contains("Place license.mclicense")),
                "an operator must be told what to do: " + log.all());
    }

    // ------------------------------------------------------------- reporting

    @Test
    @DisplayName("startup logs exactly one summary line for a healthy license")
    void oneLineOnStartup(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license().grants(Products.ESSENTIALS, "*").bytes());
        RecordingLog log = new RecordingLog();
        LicenseGate license = gate(dir, log).build();

        license.start();

        assertEquals(1, log.info.size(), "expected one line, got " + log.info);
        assertEquals(0, log.warn.size(), "a healthy license must not warn: " + log.warn);
        assertTrue(log.info.get(0).contains("VALID"));
        assertTrue(log.info.get(0).contains(Products.ESSENTIALS));
    }

    @Test
    @DisplayName("feature checks are silent, however many times they are called")
    void featureChecksDoNotLog(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license().grants(Products.ESSENTIALS, "*").bytes());
        RecordingLog log = new RecordingLog();
        LicenseGate license = gate(dir, log).build();
        license.start();
        int afterStartup = log.all().size();

        for (int i = 0; i < 1000; i++) {
            license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT);
            license.isProductLicensed(Products.ESSENTIALS);
            license.status();
        }

        assertEquals(afterStartup, log.all().size(),
                "hasFeature is called from game code; it must never log");
    }

    @Test
    @DisplayName("the grace period reminder is logged once per start, not per check")
    void graceReminderLoggedOnce(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license()
                .expiresAt("2026-07-27T00:00:00.000Z")
                .gracePeriodSeconds(259_200L)
                .grants(Products.ESSENTIALS, "*")
                .bytes());
        RecordingLog log = new RecordingLog();
        LicenseGate license = gate(dir, log).build();

        assertEquals(LicenseStatus.GRACE_PERIOD, license.start());
        assertTrue(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT),
                "grace period must keep the feature on");

        for (int i = 0; i < 100; i++) {
            license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT);
        }

        long reminders = log.warn.stream().filter(line -> line.contains("grace period")).count();
        assertEquals(1, reminders, "expected exactly one renewal reminder, saw " + log.warn);
    }

    @Test
    @DisplayName("whenLicensed runs the block only when licensed, and says so when it does not")
    void whenLicensed(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license()
                .grants(Products.ESSENTIALS, Products.Essentials.EDITOR_KIT)
                .bytes());
        RecordingLog log = new RecordingLog();
        LicenseGate license = gate(dir, log).build();
        license.start();

        AtomicInteger granted = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();

        license.whenLicensed(Products.Essentials.EDITOR_KIT, granted::incrementAndGet);
        license.whenLicensed(Products.Essentials.MODULE_CUSTOM_CONTENT, denied::incrementAndGet);

        assertEquals(1, granted.get());
        assertEquals(0, denied.get());
        assertTrue(log.info.stream().anyMatch(line ->
                        line.contains(Products.Essentials.MODULE_CUSTOM_CONTENT)
                                && line.contains("stays disabled")),
                "the operator needs to know which feature was skipped: " + log.info);
    }

    @Test
    @DisplayName("summaryLine carries what a support ticket needs")
    void summaryLine(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license().grants(Products.ESSENTIALS, "*").bytes());
        LicenseGate license = gate(dir, new RecordingLog()).build();
        license.start();

        String summary = license.summaryLine();

        assertTrue(summary.contains(Products.ESSENTIALS), summary);
        assertTrue(summary.contains("VALID"), summary);
        assertTrue(summary.contains("lic_01TESTTESTTESTTESTTESTTEST"), summary);
        assertTrue(summary.contains(TestLicenses.SERVER_UUID), summary);
    }

    @Test
    @DisplayName("licensedFeatures lists only what is actually granted")
    void licensedFeatures(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license()
                .grants(Products.ESSENTIALS,
                        Products.Essentials.EDITOR_KIT,
                        Products.Essentials.MODULE_CUSTOM_CONTENT)
                .bytes());
        LicenseGate license = gate(dir, new RecordingLog()).build();
        license.start();

        assertEquals(
                List.of(Products.Essentials.EDITOR_KIT, Products.Essentials.MODULE_CUSTOM_CONTENT),
                license.licensedFeatures(Products.Essentials.ALL));
    }

    // -------------------------------------------------------------- caching

    @Test
    @DisplayName("the license is verified once, not on every check")
    void verifiedOnce(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license().grants(Products.ESSENTIALS, "*").bytes());
        LicenseGate license = gate(dir, new RecordingLog()).build();
        license.start();

        // Deleting the file must not change the cached answer.
        Files.delete(dir.resolve(LicenseGate.LICENSE_FILE));

        assertTrue(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));
        assertEquals(LicenseStatus.VALID, license.status());
    }

    @Test
    @DisplayName("reload picks up a renewed license without a restart")
    void reloadPicksUpANewFile(@TempDir Path dir) throws Exception {
        RecordingLog log = new RecordingLog();
        LicenseGate license = gate(dir, log).build();

        assertEquals(LicenseStatus.MISSING, license.start());
        assertFalse(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));

        writeLicense(dir, licenses.license()
                .grants(Products.ESSENTIALS, Products.Essentials.MODULE_CUSTOM_CONTENT)
                .bytes());

        assertEquals(LicenseStatus.VALID, license.reload());
        assertTrue(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));
    }

    @Test
    @DisplayName("reload on a license that has gone bad revokes the feature")
    void reloadCanRevoke(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license().grants(Products.ESSENTIALS, "*").bytes());
        LicenseGate license = gate(dir, new RecordingLog()).build();
        license.start();
        assertTrue(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));

        writeLicense(dir, licenses.license()
                .grants(Products.ESSENTIALS, "*")
                .withFlippedCiphertext()
                .bytes());

        assertEquals(LicenseStatus.INVALID_SIGNATURE, license.reload());
        assertFalse(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));
    }

    // ------------------------------------------------------- failure policy

    @ParameterizedTest(name = "every accessor is safe for a {0} license file")
    @EnumSource(LicenseStatus.class)
    @DisplayName("no accessor throws, whatever is on disk")
    void accessorsAreSafeInEveryStatus(LicenseStatus status, @TempDir Path dir) throws Exception {
        LicenseGate license = gateInStatus(status, dir);

        assertDoesNotThrow(() -> {
            assertNotNull(license.status());
            license.isValid();
            license.isProductLicensed(Products.ESSENTIALS);
            license.isProductLicensed(null);
            license.hasFeature(Products.ESSENTIALS, Products.Essentials.MODULE_CUSTOM_CONTENT);
            license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT);
            license.hasFeature(null, null);
            assertNotNull(license.expiresAt());
            assertNotNull(license.licenseId());
            assertNotNull(license.summaryLine());
            assertNotNull(license.detail());
            assertNotNull(license.licensedFeatures(Products.Essentials.ALL));
            license.serverUuid();
            license.whenLicensed(Products.Essentials.MODULE_CUSTOM_CONTENT, () -> {
            });
        });
    }

    @Test
    @DisplayName("a gate that was never started grants nothing and does not throw")
    void neverStarted(@TempDir Path dir) {
        LicenseGate license = gate(dir, new RecordingLog()).build();

        assertEquals(LicenseStatus.MISSING, license.status());
        assertFalse(license.isValid());
        assertFalse(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));
        assertTrue(license.expiresAt().isEmpty());
        assertTrue(license.licenseId().isEmpty());
    }

    @Test
    @DisplayName("unusable embedded keys disable the feature instead of throwing at build time")
    void brokenKeysDoNotThrow(@TempDir Path dir) {
        RecordingLog log = new RecordingLog();

        LicenseGate license = assertDoesNotThrow(() -> LicenseGate.builder(Products.ESSENTIALS)
                .dataDir(dir)
                .withoutEmbeddedKeys()
                .logger(log)
                .writeRequestFile(false)
                .build());

        assertEquals(LicenseStatus.INVALID_FORMAT, assertDoesNotThrow(license::start));
        assertFalse(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));
    }

    @Test
    @DisplayName("a logger that throws cannot take the gate down with it")
    void throwingLoggerIsContained(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license().grants(Products.ESSENTIALS, "*").bytes());
        LicenseLog hostile = new LicenseLog() {
            @Override
            public void info(String message) {
                throw new RuntimeException("the host logger is broken");
            }

            @Override
            public void warn(String message) {
                throw new RuntimeException("the host logger is broken");
            }
        };

        LicenseGate license = LicenseGate.builder(Products.ESSENTIALS)
                .dataDir(dir)
                .withoutEmbeddedKeys()
                .trustSigningKey(TestLicenses.SIGNING_KEY_ID, licenses.publicKeySpkiBase64())
                .addContentKey(TestLicenses.CONTENT_KEY_ID, licenses.contentKeyBase64Url())
                .serverUuid(() -> TestLicenses.SERVER_UUID)
                .clock(Clock.fixed(DURING, ZoneOffset.UTC))
                .writeRequestFile(false)
                .logger(hostile)
                .build();

        assertEquals(LicenseStatus.VALID, assertDoesNotThrow(license::start));
        assertTrue(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));
    }

    @Test
    @DisplayName("a throwing server-uuid supplier degrades instead of failing")
    void throwingServerUuidSupplier(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license().unbound().grants(Products.ESSENTIALS, "*").bytes());
        RecordingLog log = new RecordingLog();

        LicenseGate license = gate(dir, log)
                .serverUuid(() -> {
                    throw new IllegalStateException("the server is not ready yet");
                })
                .build();

        // The supplier failed, so the gate falls back to the persisted identity
        // and the unbound license still verifies.
        assertEquals(LicenseStatus.VALID, assertDoesNotThrow(license::start));
    }

    @Test
    @DisplayName("a non-UUID server id is reported, not thrown")
    void malformedServerUuid(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license().unbound().grants(Products.ESSENTIALS, "*").bytes());
        RecordingLog log = new RecordingLog();

        LicenseGate license = gate(dir, log).serverUuid(() -> "definitely-not-a-uuid").build();

        assertDoesNotThrow(license::start);
        assertTrue(log.warn.stream().anyMatch(line -> line.contains("is not a UUID")),
                log.warn::toString);
    }

    @Test
    @DisplayName("an unknown server identity still honours an unbound license")
    void unknownIdentityAllowsUnboundLicense(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license().unbound().grants(Products.ESSENTIALS, "*").bytes());
        Files.writeString(dir.resolve(ServerIdentity.IDENTITY_FILE), "corrupt");

        LicenseGate license = gate(dir, new RecordingLog()).serverUuid(null).build();

        assertEquals(LicenseStatus.VALID, license.start());
        assertTrue(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));
    }

    @Test
    @DisplayName("an unknown server identity refuses a server-bound license")
    void unknownIdentityRefusesBoundLicense(@TempDir Path dir) throws Exception {
        writeLicense(dir, licenses.license()
                .boundTo(TestLicenses.SERVER_UUID)
                .grants(Products.ESSENTIALS, "*")
                .bytes());
        Files.writeString(dir.resolve(ServerIdentity.IDENTITY_FILE), "corrupt");
        RecordingLog log = new RecordingLog();

        LicenseGate license = gate(dir, log).serverUuid(null).build();

        assertEquals(LicenseStatus.WRONG_SERVER, license.start(),
                "granting a binding we cannot check would defeat the binding");
        assertFalse(license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT));
        assertTrue(log.warn.stream().anyMatch(line -> line.contains("does not contain a valid UUID")),
                log.warn::toString);
        assertTrue(log.warn.stream().anyMatch(line -> line.contains("could not be read")),
                "the advice must name the file to fix: " + log.warn);
    }

    @Test
    @DisplayName("a corrupt identity file is left alone rather than silently replaced")
    void corruptIdentityIsPreserved(@TempDir Path dir) throws Exception {
        Path identity = dir.resolve(ServerIdentity.IDENTITY_FILE);
        Files.createDirectories(dir);
        Files.writeString(identity, "corrupt");

        gate(dir, new RecordingLog()).serverUuid(null).build().start();

        assertEquals("corrupt", Files.readString(identity),
                "regenerating would orphan the operator's existing license");
    }

    // --------------------------------------------------------- integration

    @Test
    @DisplayName("the license file location can be redirected")
    void customLicenseLocation(@TempDir Path dir) throws Exception {
        Path elsewhere = dir.resolve("config").resolve("mystic.license");
        Files.createDirectories(elsewhere.getParent());
        Files.write(elsewhere, licenses.license().grants(Products.ESSENTIALS, "*").bytes());

        LicenseGate license = gate(dir, new RecordingLog())
                .licenseFile(() -> elsewhere)
                .build();

        assertEquals(LicenseStatus.VALID, license.start());
    }

    @Test
    @DisplayName("without an override the gate persists a server identity itself")
    void serverIdentityIsPersisted(@TempDir Path dir) throws Exception {
        RecordingLog log = new RecordingLog();
        LicenseGate license = LicenseGate.builder(Products.ESSENTIALS)
                .dataDir(dir)
                .withoutEmbeddedKeys()
                .trustSigningKey(TestLicenses.SIGNING_KEY_ID, licenses.publicKeySpkiBase64())
                .addContentKey(TestLicenses.CONTENT_KEY_ID, licenses.contentKeyBase64Url())
                .clock(Clock.fixed(DURING, ZoneOffset.UTC))
                .writeRequestFile(false)
                .logger(log)
                .build();

        license.start();

        Path identity = dir.resolve(ServerIdentity.IDENTITY_FILE);
        assertTrue(Files.exists(identity), "the server id must be persisted on first run");
        UUID persisted = UUID.fromString(
                Files.readString(identity, StandardCharsets.UTF_8).trim());
        assertEquals(persisted, license.serverUuid());
    }

    @Test
    @DisplayName("a license request file is written when there is no license")
    void writesLicenseRequest(@TempDir Path dir) {
        LicenseGate license = gate(dir, new RecordingLog())
                .writeRequestFile(true)
                .serverName("Test Server")
                .modVersion("1.0.1")
                .build();

        license.start();

        assertTrue(Files.exists(dir.resolve(ServerIdentity.REQUEST_FILE)));
    }

    @Test
    @DisplayName("the gate reports the product it was built for")
    void productIdIsExposed(@TempDir Path dir) {
        assertSame(Products.ESSENTIALS, gate(dir, new RecordingLog()).build().productId());
    }

    // ------------------------------------------------------------------ util

    /**
     * Builds a gate parked in the requested status, so the accessor-safety test
     * genuinely covers all thirteen rather than only the easy ones.
     */
    private LicenseGate gateInStatus(LicenseStatus status, Path dir) throws Exception {
        RecordingLog log = new RecordingLog();
        LicenseGate.Builder builder = gate(dir, log);

        switch (status) {
            case VALID -> writeLicense(dir, licenses.license()
                    .grants(Products.ESSENTIALS, "*").bytes());
            case GRACE_PERIOD -> writeLicense(dir, licenses.license()
                    .expiresAt("2026-07-27T00:00:00.000Z")
                    .gracePeriodSeconds(259_200L)
                    .grants(Products.ESSENTIALS, "*").bytes());
            case EXPIRED -> writeLicense(dir, licenses.license()
                    .expiresAt("2026-01-01T00:00:00.000Z")
                    .gracePeriodSeconds(0L)
                    .grants(Products.ESSENTIALS, "*").bytes());
            case NOT_YET_VALID -> writeLicense(dir, licenses.license()
                    .notBefore("2027-01-01T00:00:00.000Z")
                    .expiresAt("2027-06-01T00:00:00.000Z")
                    .grants(Products.ESSENTIALS, "*").bytes());
            case WRONG_SERVER -> writeLicense(dir, licenses.license()
                    .boundTo(TestLicenses.OTHER_SERVER_UUID)
                    .grants(Products.ESSENTIALS, "*").bytes());
            case WRONG_PRODUCT ->
                // Not reachable from verifyFile: it is the answer checkFeature
                // gives, so the gate parks on a license for another product.
                    writeLicense(dir, licenses.license()
                            .grants(Products.GUILDS, "*").bytes());
            case INVALID_SIGNATURE -> writeLicense(dir, licenses.license()
                    .grants(Products.ESSENTIALS, "*")
                    .withFlippedSignature().bytes());
            case DECRYPTION_FAILED -> writeLicense(dir, licenses.license()
                    .grants(Products.ESSENTIALS, "*")
                    .encryptedWith(TestLicenses.newContentKey()).bytes());
            case UNKNOWN_SIGNING_KEY -> writeLicense(dir, licenses.license()
                    .signingKeyId("nope").grants(Products.ESSENTIALS, "*").bytes());
            case UNKNOWN_ENCRYPTION_KEY -> writeLicense(dir, licenses.license()
                    .encryptionKeyId("nope").grants(Products.ESSENTIALS, "*").bytes());
            case UNSUPPORTED_VERSION -> writeLicense(dir, licenses.license()
                    .envelopeVersion(99).grants(Products.ESSENTIALS, "*").bytes());
            case INVALID_FORMAT -> writeLicense(dir,
                    "this is not a license".getBytes(StandardCharsets.UTF_8));
            case MISSING -> {
                // no file at all
            }
        }

        LicenseGate license = builder.build();
        license.start();
        return license;
    }
}
