package com.alphine.mysticessentials.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Simple Modrinth update checker.
 *
 * On server startup, call:
 *   ModrinthUpdateChecker.checkForUpdatesAsync("your-project-slug", currentVersion, logFn);
 *
 * It will:
 *   - query Modrinth for the latest *featured* version of the project
 *   - compare version_number with currentVersion
 *   - log if a newer version exists
 */
public final class ModrinthUpdateChecker {

    private static final String API_BASE = "https://api.modrinth.com/v2/project/";
    private static final AtomicBoolean RAN = new AtomicBoolean(false);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private ModrinthUpdateChecker() {}

    /**
     * @param projectSlug    Modrinth project slug or ID (e.g. "mysticessentials")
     * @param currentVersion Your mod's current version string
     * @param log            Logger consumer (e.g. msg -> LOGGER.info(msg) )
     */
    public static void checkForUpdatesAsync(String projectSlug,
                                            String currentVersion,
                                            Consumer<String> log) {
        if (!RAN.compareAndSet(false, true)) {
            return; // only run once per JVM
        }

        CompletableFuture.runAsync(() -> {
            try {
                String url = API_BASE + projectSlug + "/version?featured=true";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "MysticEssentials/" + currentVersion + " (update-checker)")
                        .GET()
                        .build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    log.accept("[MysticEssentials] Modrinth update check failed: HTTP "
                            + response.statusCode());
                    return;
                }

                JsonArray arr = JsonParser.parseString(response.body()).getAsJsonArray();
                if (arr.isEmpty()) {
                    log.accept("[MysticEssentials] Modrinth update check: no versions found.");
                    return;
                }

                JsonObject latest = arr.get(0).getAsJsonObject();
                String latestVersion = latest.get("version_number").getAsString();
                String versionType = latest.has("version_type")
                        ? latest.get("version_type").getAsString()
                        : "release";

                if (equalsIgnoreCaseTrim(currentVersion, latestVersion)) {
                    log.accept("[MysticEssentials] You are running the latest version (" + currentVersion + ").");
                } else {
                    String urlPage = "https://modrinth.com/mod/" + projectSlug;
                    log.accept("[MysticEssentials] A new version is available on Modrinth: "
                            + latestVersion + " (" + versionType + ") - you are on " + currentVersion
                            + ". Download: " + urlPage);
                }

            } catch (Exception ex) {
                log.accept("[MysticEssentials] Modrinth update check failed: " + ex.getMessage());
            }
        });
    }

    private static boolean equalsIgnoreCaseTrim(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }
}
