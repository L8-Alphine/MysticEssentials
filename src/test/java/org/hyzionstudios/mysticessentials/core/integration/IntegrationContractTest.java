package org.hyzionstudios.mysticessentials.core.integration;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/** Build-time checks for optional dependency metadata and the external APIs Mystic calls. */
public final class IntegrationContractTest {

    private IntegrationContractTest() {
    }

    public static void main(String[] args) throws Exception {
        manifestKeepsBridgesOptional();
        luckPermsContractIsPresent();
        placeholderApiContractIsPresent();
        vaultUnlockedContractIsPresent();
        storageAndGuiLibrariesArePresent();
        localMysticBridgesRemainUnbundled();
    }

    private static void manifestKeepsBridgesOptional() throws Exception {
        JsonObject manifest;
        try (var stream = IntegrationContractTest.class.getResourceAsStream("/manifest.json")) {
            require(stream != null, "manifest.json was not on the test classpath");
            manifest = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }

        require(manifest.getAsJsonObject("Dependencies").isEmpty(),
                "optional integrations were accidentally made hard dependencies");
        JsonObject optional = manifest.getAsJsonObject("OptionalDependencies");
        Map<String, String> expected = Map.of(
                "HelpChat:PlaceholderAPI", ">=1.0.8",
                "LuckPerms:LuckPerms", "*",
                "TheNewEconomy:VaultUnlocked", ">=2.20.0",
                "org.hyzionstudios:MysticVanish", ">=1.0.0",
                "org.hyzionstudios:mysticrpg", ">=1.0.0",
                "org.hyzionstudios:mysticidentity", "*");
        require(optional.size() == expected.size(),
                "manifest optional dependency set drifted: " + optional.keySet());
        expected.forEach((id, range) -> require(optional.has(id)
                        && range.equals(optional.get(id).getAsString()),
                "manifest is missing optional dependency " + id + " " + range));
    }

    private static void luckPermsContractIsPresent() throws Exception {
        Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
        requireMethod(provider, "get");
        Class<?> luckPerms = Class.forName("net.luckperms.api.LuckPerms");
        requireMethod(luckPerms, "getUserManager");
        requireMethod(luckPerms, "getEventBus");
    }

    private static void placeholderApiContractIsPresent() throws Exception {
        Class<?> api = Class.forName("at.helpch.placeholderapi.PlaceholderAPI");
        requireMethod(api, "setPlaceholders", PlayerRef.class, String.class);
        Class<?> expansion = Class.forName(
                "at.helpch.placeholderapi.expansion.PlaceholderExpansion");
        requireMethod(expansion, "register");
        requireMethod(expansion, "unregister");
        requireMethod(expansion, "isRegistered");
    }

    private static void vaultUnlockedContractIsPresent() throws Exception {
        Class<?> vault = Class.forName("net.cfh.vault.VaultUnlocked");
        Method economyLookup = requireMethod(vault, "economy");
        require(Optional.class.equals(economyLookup.getReturnType()),
                "VaultUnlocked.economy() no longer returns Optional");

        Class<?> economy = Class.forName("net.milkbowl.vault2.economy.Economy");
        requireMethod(economy, "isEnabled");
        requireMethod(economy, "balance", String.class, UUID.class);
        requireMethod(economy, "has", String.class, UUID.class, BigDecimal.class);
        requireMethod(economy, "withdraw", String.class, UUID.class, BigDecimal.class);
        requireMethod(economy, "deposit", String.class, UUID.class, BigDecimal.class);
        requireMethod(economy, "format", String.class, BigDecimal.class);
        requireMethod(economy, "createAccount", UUID.class, String.class, boolean.class);
    }

    private static void storageAndGuiLibrariesArePresent() throws Exception {
        requireClass("com.zaxxer.hikari.HikariDataSource");
        requireClass("org.mariadb.jdbc.Driver");
        requireClass("com.mysql.cj.jdbc.Driver");
        requireClass("redis.clients.jedis.RedisClient");
        requireClass("redis.clients.jedis.DefaultJedisClientConfig");
        requireClass("org.jsoup.Jsoup");
    }

    private static void localMysticBridgesRemainUnbundled() throws Exception {
        requireAbsent("org.hyzionstudios.mysticvanish.api.MysticVanishProvider");
        requireAbsent("org.hyzionstudios.mysticmoderation.api.MysticModerationProvider");
        requireAbsent("org.hyzionstudios.mysticidentity.MysticIdentityProvider");
        Class.forName("org.hyzionstudios.mysticessentials.core.integration.VanishBridge");
        Class.forName("org.hyzionstudios.mysticessentials.core.integration.ModerationBridge");
        Class.forName("org.hyzionstudios.mysticessentials.core.integration.ManagedAccountsBridge");
    }

    private static Method requireMethod(Class<?> type, String name, Class<?>... parameters)
            throws Exception {
        Method method = type.getMethod(name, parameters);
        require(method != null, type.getName() + " is missing " + name);
        return method;
    }

    private static void requireAbsent(String className) throws Exception {
        try {
            Class.forName(className);
            throw new AssertionError(className + " was bundled despite being a soft integration");
        } catch (ClassNotFoundException expected) {
            // Correct: the server supplies this API only when its plugin is installed.
        }
    }

    private static Class<?> requireClass(String className) throws Exception {
        return Class.forName(className, false, IntegrationContractTest.class.getClassLoader());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
