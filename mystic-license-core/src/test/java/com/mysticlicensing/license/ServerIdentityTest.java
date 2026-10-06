package com.mysticlicensing.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

        assertEquals(canonical.toLowerCase(Locale.ROOT), canonical);
        assertDoesNotThrow(() -> UUID.fromString(canonical));
    }

    // ------------------------------------------------------------ shared id

    private static Path sharedFile(Path mods) {
        return mods.resolve(ServerIdentity.SHARED_DIR).resolve(ServerIdentity.IDENTITY_FILE);
    }

    private static UUID perMod(Path mods, String mod) throws Exception {
        UUID uuid = UUID.randomUUID();
        perMod(mods, mod, uuid.toString());
        return uuid;
    }

    private static void perMod(Path mods, String mod, String content) throws Exception {
        Path dir = mods.resolve(mod);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(ServerIdentity.IDENTITY_FILE), content, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).trim();
    }

    @Test
    @DisplayName("with no per-mod ids the shared id is generated in mods/.mystic")
    void sharedIdentityIsCreated(@TempDir Path mods) throws Exception {
        ServerIdentity.Result result = ServerIdentity.resolveShared(mods, mods.resolve("MysticGuilds"));

        assertEquals(ServerIdentity.Outcome.CREATED, result.outcome());
        assertEquals(result.uuid().toString(), read(sharedFile(mods)));
        assertFalse(Files.exists(mods.resolve("MysticGuilds").resolve(ServerIdentity.IDENTITY_FILE)),
                "nothing is kept per mod any more");
    }

    @Test
    @DisplayName("every mod resolves the same shared id")
    void sharedIdentityIsShared(@TempDir Path mods) {
        UUID first = ServerIdentity.resolveShared(mods, mods.resolve("MysticEssentials")).uuid();
        ServerIdentity.Result second = ServerIdentity.resolveShared(mods, mods.resolve("MysticGuilds"));

        assertEquals(ServerIdentity.Outcome.LOADED, second.outcome());
        assertEquals(first, second.uuid());
    }

    @Test
    @DisplayName("a single per-mod id is adopted and left in place")
    void singlePerModIdentityIsMigrated(@TempDir Path mods) throws Exception {
        UUID existing = perMod(mods, "MysticGuilds");

        ServerIdentity.Result result = ServerIdentity.resolveShared(mods, mods.resolve("MysticEconomy"));

        assertEquals(ServerIdentity.Outcome.MIGRATED, result.outcome());
        assertEquals(existing, result.uuid());
        assertNull(result.detail(), "nothing disagreed, so there is nothing to warn about");
        assertEquals(existing.toString(), read(sharedFile(mods)));
        assertEquals(existing.toString(), read(mods.resolve("MysticGuilds").resolve(ServerIdentity.IDENTITY_FILE)),
                "older mod versions still read their own file");
        assertEquals(ServerIdentity.Outcome.LOADED,
                ServerIdentity.resolveShared(mods, mods.resolve("MysticGuilds")).outcome());
    }

    @Test
    @DisplayName("per-mod ids that agree are adopted without a warning")
    void agreeingPerModIdentitiesAreMigrated(@TempDir Path mods) throws Exception {
        UUID existing = perMod(mods, "MysticEssentials");
        perMod(mods, "MysticGuilds", existing.toString().toUpperCase(Locale.ROOT));

        ServerIdentity.Result result = ServerIdentity.resolveShared(mods, mods.resolve("MysticGuilds"));

        assertEquals(ServerIdentity.Outcome.MIGRATED, result.outcome());
        assertEquals(existing, result.uuid());
        assertNull(result.detail());
    }

    @Test
    @DisplayName("when per-mod ids differ, MysticEssentials' id wins and the others are named")
    void disagreeingPerModIdentitiesPreferEssentials(@TempDir Path mods) throws Exception {
        UUID essentials = perMod(mods, "MysticEssentials");
        UUID guilds = perMod(mods, "MysticGuilds");

        ServerIdentity.Result result = ServerIdentity.resolveShared(mods, mods.resolve("MysticGuilds"));

        assertEquals(ServerIdentity.Outcome.MIGRATED, result.outcome());
        assertEquals(essentials, result.uuid());
        assertEquals(essentials.toString(), read(sharedFile(mods)));
        assertNotNull(result.detail());
        assertTrue(result.detail().contains(guilds.toString()), result.detail());
        assertTrue(result.detail().contains("MysticGuilds"), result.detail());
    }

    @Test
    @DisplayName("without MysticEssentials, the starting mod's own id wins")
    void disagreeingPerModIdentitiesPreferOwn(@TempDir Path mods) throws Exception {
        UUID economy = perMod(mods, "MysticEconomy");
        UUID guilds = perMod(mods, "MysticGuilds");

        ServerIdentity.Result result = ServerIdentity.resolveShared(mods, mods.resolve("MysticGuilds"));

        assertEquals(guilds, result.uuid());
        assertTrue(result.detail().contains(economy.toString()), result.detail());
    }

    @Test
    @DisplayName("the starting mod's own id is found even outside the mods folder")
    void ownDataDirectoryOutsideModsIsSearched(@TempDir Path dir) throws Exception {
        Path mods = dir.resolve("mods");
        UUID own = perMod(dir, "elsewhere");

        ServerIdentity.Result result = ServerIdentity.resolveShared(mods, dir.resolve("elsewhere"));

        assertEquals(ServerIdentity.Outcome.MIGRATED, result.outcome());
        assertEquals(own, result.uuid());
    }

    @Test
    @DisplayName("another mod's server-id.txt is not mistaken for a Mystic one")
    void nonMysticFoldersAreIgnored(@TempDir Path mods) throws Exception {
        UUID foreign = perMod(mods, "SomeOtherMod");

        ServerIdentity.Result result = ServerIdentity.resolveShared(mods, mods.resolve("MysticGuilds"));

        assertEquals(ServerIdentity.Outcome.CREATED, result.outcome());
        assertNotEquals(foreign, result.uuid());
    }

    @Test
    @DisplayName("a corrupt per-mod id with nothing else is reported, not replaced")
    void corruptPerModIdentityIsNotReplaced(@TempDir Path mods) throws Exception {
        perMod(mods, "MysticEssentials", "this is not a uuid");

        ServerIdentity.Result result = ServerIdentity.resolveShared(mods, mods.resolve("MysticEssentials"));

        assertEquals(ServerIdentity.Outcome.CORRUPT, result.outcome());
        assertNull(result.uuid());
        assertFalse(Files.exists(sharedFile(mods)),
                "a new id would orphan the license bound to the unreadable one");
        assertEquals("this is not a uuid",
                read(mods.resolve("MysticEssentials").resolve(ServerIdentity.IDENTITY_FILE)));
    }

    @Test
    @DisplayName("a corrupt per-mod id beside a valid one is skipped and named")
    void corruptPerModIdentityBesideAValidOne(@TempDir Path mods) throws Exception {
        perMod(mods, "MysticEssentials", "this is not a uuid");
        UUID guilds = perMod(mods, "MysticGuilds");

        ServerIdentity.Result result = ServerIdentity.resolveShared(mods, mods.resolve("MysticEssentials"));

        assertEquals(ServerIdentity.Outcome.MIGRATED, result.outcome());
        assertEquals(guilds, result.uuid());
        assertTrue(result.detail().contains("Unreadable"), result.detail());
    }

    @Test
    @DisplayName("an existing shared id wins over per-mod ids")
    void sharedIdentityWinsOverPerMod(@TempDir Path mods) throws Exception {
        UUID shared = perMod(mods, ServerIdentity.SHARED_DIR);
        perMod(mods, "MysticEssentials");

        ServerIdentity.Result result = ServerIdentity.resolveShared(mods, mods.resolve("MysticEssentials"));

        assertEquals(ServerIdentity.Outcome.LOADED, result.outcome());
        assertEquals(shared, result.uuid());
    }

    @Test
    @DisplayName("a corrupt shared id is reported, never replaced by a per-mod one")
    void corruptSharedIdentityIsNotReplaced(@TempDir Path mods) throws Exception {
        perMod(mods, ServerIdentity.SHARED_DIR, "corrupt");
        perMod(mods, "MysticEssentials");

        ServerIdentity.Result result = ServerIdentity.resolveShared(mods, mods.resolve("MysticEssentials"));

        assertEquals(ServerIdentity.Outcome.CORRUPT, result.outcome());
        assertEquals("corrupt", read(sharedFile(mods)));
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
