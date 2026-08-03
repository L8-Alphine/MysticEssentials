package com.mysticlicensing.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerIdentityTest {

    @Test
    @DisplayName("first run generates and persists an identity")
    void firstRunCreatesIdentity(@TempDir Path dir) {
        ServerIdentity.Result result = ServerIdentity.resolve(dir);

        assertEquals(ServerIdentity.Outcome.CREATED, result.outcome());
        assertNotNull(result.uuid());
        assertTrue(Files.exists(dir.resolve(ServerIdentity.IDENTITY_FILE)));
    }

    @Test
    @DisplayName("the identity is stable across restarts")
    void identityIsStable(@TempDir Path dir) {
        UUID first = ServerIdentity.resolve(dir).uuid();
        ServerIdentity.Result second = ServerIdentity.resolve(dir);

        assertEquals(ServerIdentity.Outcome.LOADED, second.outcome());
        assertEquals(first, second.uuid());
    }

    @Test
    @DisplayName("a corrupt identity file is reported, never silently replaced")
    void corruptIdentityIsNotOverwritten(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(ServerIdentity.IDENTITY_FILE);
        Files.writeString(file, "this is not a uuid", StandardCharsets.UTF_8);

        ServerIdentity.Result result = ServerIdentity.resolve(dir);

        assertEquals(ServerIdentity.Outcome.CORRUPT, result.outcome());
        assertNull(result.uuid());
        assertNotNull(result.detail());
        assertEquals("this is not a uuid", Files.readString(file, StandardCharsets.UTF_8),
                "overwriting would orphan the operator's existing license");
    }

    @Test
    @DisplayName("an annotated identity file still parses")
    void trailingCommentIsTolerated(@TempDir Path dir) throws Exception {
        UUID uuid = UUID.randomUUID();
        Files.writeString(dir.resolve(ServerIdentity.IDENTITY_FILE),
                uuid + "\n# do not edit - this is what the portal binds licenses to\n",
                StandardCharsets.UTF_8);

        assertEquals(uuid, ServerIdentity.load(dir).orElseThrow());
    }

    @Test
    @DisplayName("a missing directory is empty, not an error")
    void missingDirectory(@TempDir Path dir) {
        assertTrue(ServerIdentity.load(dir.resolve("not-created")).isEmpty());
    }

    @Test
    @DisplayName("the canonical form is lowercase, which is what the portal binds")
    void canonicalFormIsLowercase(@TempDir Path dir) {
        String canonical = ServerIdentity.resolve(dir).canonical().orElseThrow();

        assertEquals(canonical.toLowerCase(java.util.Locale.ROOT), canonical);
        assertDoesNotThrow(() -> UUID.fromString(canonical));
    }

    @Test
    @DisplayName("a license request file matches the portal's importer schema")
    void licenseRequestFile(@TempDir Path dir) throws Exception {
        UUID uuid = UUID.randomUUID();

        Path file = ServerIdentity.writeLicenseRequest(
                dir, uuid, "Test Server", Products.ESSENTIALS, "1.0.1");

        var json = MiniJson.asObject(MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8)));
        assertNotNull(json);
        assertEquals(1L, MiniJson.asLong(json.get("format_version")));
        assertEquals(uuid.toString(), MiniJson.asString(json.get("server_uuid")));
        assertEquals("Test Server", MiniJson.asString(json.get("server_name")));
        assertEquals(Products.ESSENTIALS, MiniJson.asString(json.get("product_id")));
        assertEquals("1.0.1", MiniJson.asString(json.get("mod_version")));
        assertNotNull(MiniJson.asString(json.get("request_nonce")));
        assertNotNull(MiniJson.asString(json.get("created_at")));
    }

    @Test
    @DisplayName("a request file omits an absent server name rather than writing null")
    void licenseRequestWithoutServerName(@TempDir Path dir) throws Exception {
        Path file = ServerIdentity.writeLicenseRequest(
                dir, UUID.randomUUID(), null, Products.ESSENTIALS, null);

        var json = MiniJson.asObject(MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8)));
        assertFalse(json.containsKey("server_name"),
                "the portal's importer rejects unknown and null fields");
        assertFalse(json.containsKey("mod_version"));
    }

    @Test
    @DisplayName("a server name with quotes does not break the request file")
    void serverNameIsEscaped(@TempDir Path dir) throws Exception {
        Path file = ServerIdentity.writeLicenseRequest(
                dir, UUID.randomUUID(), "Bob\"s \\ Server\n", Products.ESSENTIALS, "1.0");

        var json = assertDoesNotThrow(() -> MiniJson.asObject(
                MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8))));
        assertNotNull(json);
        assertEquals("Bob\"s \\ Server", MiniJson.asString(json.get("server_name")));
    }
}
