package com.mysticlicensing.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoopMysticLicenseServiceTest {

    private final MysticLicenseService service = NoopMysticLicenseService.INSTANCE;

    @Test
    @DisplayName("reports MISSING and grants nothing")
    void grantsNothing() {
        assertEquals(LicenseStatus.MISSING, service.status());
        assertFalse(service.isValid());
        assertFalse(service.isProductLicensed(Products.ESSENTIALS));
        assertFalse(service.hasFeature(Products.ESSENTIALS,
                Products.Essentials.MODULE_CUSTOM_CONTENT));
        assertTrue(service.expiresAt().isEmpty());
        assertTrue(service.licenseId().isEmpty());
    }

    @Test
    @DisplayName("tolerates null arguments like every other implementation")
    void nullArguments() {
        assertFalse(service.isProductLicensed(null));
        assertFalse(service.hasFeature(null, null));
    }

    @Test
    @DisplayName("the wildcard does not sneak through")
    void wildcardIsNotSpecial() {
        assertFalse(service.isProductLicensed("*"));
        assertFalse(service.hasFeature("*", "*"));
    }
}
