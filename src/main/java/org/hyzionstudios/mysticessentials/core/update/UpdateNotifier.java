package org.hyzionstudios.mysticessentials.core.update;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.config.MainConfig;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Checks public CurseForge project metadata for a newer published JAR and
 * notifies authorized players as they join. HTTP is always asynchronous and a
 * failed check only disables notices until a later check succeeds.
 */
public final class UpdateNotifier {

    public static final String CURSEFORGE_URL =
            "https://www.curseforge.com/hytale/mods/mysticessentials";
    private static final String UPDATE_API_URL = "https://api.cfwidget.com/1600567";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Pattern FILE_VERSION = Pattern.compile(
            "(?i)MysticEssentials[-_ ]v?(\\d+(?:\\.\\d+)+(?:-[0-9a-z][0-9a-z.-]*)?)\\.jar");

    private final MysticCore core;
    private final HttpClient client;
    private final Object checkLock = new Object();

    private volatile Release latestRelease;
    private volatile CompletableFuture<Release> inFlight;
    private ScheduledFuture<?> repeatingTask;

    public UpdateNotifier(MysticCore core) {
        this.core = core;
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Starts the initial check and the configured periodic refresh. */
    public void start() {
        MainConfig.UpdateNotifier config = config();
        if (!config.enabled) {
            return;
        }
        checkNow();
        repeatingTask = core.scheduler().runRepeating(this::checkNow,
                config.checkIntervalHours, config.checkIntervalHours, TimeUnit.HOURS);
    }

    /** Applies changed settings after {@code /mystic reload}. */
    public void reload() {
        stop();
        start();
    }

    public void stop() {
        if (repeatingTask != null) {
            repeatingTask.cancel(false);
            repeatingTask = null;
        }
    }

    /**
     * Sends the cached update notice, or waits for the startup check if the
     * player joined before it completed. The player and permission are checked
     * again before a delayed notice is sent.
     */
    public void notifyOnJoin(PlayerRef player) {
        if (!canNotify(player)) {
            return;
        }
        Release release = latestRelease;
        if (isOutdated(release)) {
            sendNotice(player, release);
            return;
        }

        CompletableFuture<Release> pending = inFlight;
        if (pending != null) {
            pending.thenAccept(found -> core.platform().findPlayer(player.getUuid())
                    .filter(live -> live == player)
                    .filter(this::canNotify)
                    .filter(live -> isOutdated(found))
                    .ifPresent(live -> sendNotice(live, found)));
        }
    }

    /** Executes one asynchronous refresh, coalescing concurrent requests. */
    private CompletableFuture<Release> checkNow() {
        synchronized (checkLock) {
            if (inFlight != null && !inFlight.isDone()) {
                return inFlight;
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(UPDATE_API_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "MysticEssentials/" + core.getVersion()
                            + " (update check; " + CURSEFORGE_URL + ")")
                    .GET()
                    .build();

            CompletableFuture<Release> check = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> parseResponse(response.statusCode(), response.body()));
            inFlight = check;
            check.whenComplete((release, error) -> {
                if (error == null) {
                    Release previous = latestRelease;
                    latestRelease = release;
                    if (isOutdated(release) && (previous == null || !previous.version.equals(release.version))) {
                        core.log(Level.WARNING, "Mystic Essentials v" + release.version
                                + " is available (running v" + core.getVersion() + "): " + CURSEFORGE_URL);
                    }
                } else {
                    core.log(Level.WARNING, "Could not check CurseForge for Mystic Essentials updates: "
                            + rootMessage(error));
                }
                synchronized (checkLock) {
                    if (inFlight == check) {
                        inFlight = null;
                    }
                }
            });
            return check;
        }
    }

    private Release parseResponse(int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("CurseForge returned HTTP " + statusCode);
        }
        String version = publishedVersion(body);
        if (version == null) {
            throw new IllegalStateException("no MysticEssentials JAR version was found in CurseForge metadata");
        }
        return new Release(version);
    }

    private boolean canNotify(PlayerRef player) {
        MainConfig.UpdateNotifier config = config();
        return config.enabled && config.notifyOnJoin && player.hasPermission(Permissions.UPDATE_NOTIFY);
    }

    private boolean isOutdated(Release release) {
        return release != null && compareVersions(release.version, core.getVersion()) > 0;
    }

    private void sendNotice(PlayerRef player, Release release) {
        core.getMessageService().sendKey(player, "update-notifier-available", Map.of(
                "current", core.getVersion(),
                "latest", release.version,
                "url", CURSEFORGE_URL));
    }

    private MainConfig.UpdateNotifier config() {
        MainConfig.UpdateNotifier config = core.config().updateNotifier;
        return config == null ? new MainConfig.UpdateNotifier() : config;
    }

    static String newestPublishedVersion(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Matcher matcher = FILE_VERSION.matcher(html);
        String newest = null;
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (newest == null || compareVersions(candidate, newest) > 0) {
                newest = candidate;
            }
        }
        return newest;
    }

    /**
     * CFWidget selects the newest release file in {@code download} (falling
     * back to beta/alpha only when no release exists). Prefer that selection so
     * an unrelated old file or a future version mentioned in the description
     * cannot trigger a notice.
     */
    static String publishedVersion(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("download") && root.get("download").isJsonObject()) {
                JsonObject download = root.getAsJsonObject("download");
                for (String field : List.of("name", "display")) {
                    if (download.has(field) && download.get(field).isJsonPrimitive()) {
                        String version = newestPublishedVersion(download.get(field).getAsString());
                        if (version != null) {
                            return version;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Keep a tolerant fallback for backwards-compatible API responses.
        }
        return newestPublishedVersion(json);
    }

    /** Compares numeric versions and common SemVer-style prerelease suffixes. */
    static int compareVersions(String left, String right) {
        ParsedVersion a = ParsedVersion.parse(left);
        ParsedVersion b = ParsedVersion.parse(right);
        int size = Math.max(a.numbers.size(), b.numbers.size());
        for (int i = 0; i < size; i++) {
            BigInteger av = i < a.numbers.size() ? a.numbers.get(i) : BigInteger.ZERO;
            BigInteger bv = i < b.numbers.size() ? b.numbers.get(i) : BigInteger.ZERO;
            int compared = av.compareTo(bv);
            if (compared != 0) {
                return compared;
            }
        }
        if (a.qualifier.isEmpty() && b.qualifier.isEmpty()) {
            return 0;
        }
        if (a.qualifier.isEmpty()) {
            return 1;
        }
        if (b.qualifier.isEmpty()) {
            return -1;
        }
        return compareQualifier(a.qualifier, b.qualifier);
    }

    private static int compareQualifier(String left, String right) {
        String[] a = left.split("[.-]");
        String[] b = right.split("[.-]");
        int size = Math.max(a.length, b.length);
        for (int i = 0; i < size; i++) {
            if (i >= a.length) {
                return -1;
            }
            if (i >= b.length) {
                return 1;
            }
            boolean an = a[i].chars().allMatch(Character::isDigit);
            boolean bn = b[i].chars().allMatch(Character::isDigit);
            int compared;
            if (an && bn) {
                compared = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
            } else if (an != bn) {
                compared = an ? -1 : 1;
            } else {
                compared = a[i].compareToIgnoreCase(b[i]);
            }
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Release(String version) {
    }

    private record ParsedVersion(List<BigInteger> numbers, String qualifier) {
        static ParsedVersion parse(String raw) {
            String normalized = raw == null ? "0" : raw.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("v")) {
                normalized = normalized.substring(1);
            }
            int build = normalized.indexOf('+');
            if (build >= 0) {
                normalized = normalized.substring(0, build);
            }
            String numeric = normalized;
            String qualifier = "";
            int dash = normalized.indexOf('-');
            if (dash >= 0) {
                numeric = normalized.substring(0, dash);
                qualifier = normalized.substring(dash + 1);
            }
            List<BigInteger> numbers = new ArrayList<>();
            for (String part : numeric.split("\\.")) {
                numbers.add(part.chars().allMatch(Character::isDigit) && !part.isEmpty()
                        ? new BigInteger(part)
                        : BigInteger.ZERO);
            }
            return new ParsedVersion(numbers, qualifier);
        }
    }
}
