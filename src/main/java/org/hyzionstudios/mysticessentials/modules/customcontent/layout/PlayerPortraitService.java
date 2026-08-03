package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.common.util.ArrayUtil;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.setup.AssetFinalize;
import com.hypixel.hytale.protocol.packets.setup.AssetInitialize;
import com.hypixel.hytale.protocol.packets.setup.AssetPart;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.io.PacketHandler;

/** Cached, slot-based player portrait delivery adapted from the supplied SkinService. */
public final class PlayerPortraitService {
    private static final Duration FAILED_TTL = Duration.ofMinutes(15);
    private static final String TOKEN = "{username}";
    private static final String UI_PREFIX = "MysticEssentials/portraits/";
    private static final String ASSET_PREFIX = "UI/Custom/" + UI_PREFIX;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    private final MysticCore core;
    private final Path cacheDirectory;
    private final String apiTemplate;
    private final Duration cacheTtl;
    private final Consumer<String> available;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService downloads = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "MysticEssentials-PortraitDownloader");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Instant> failures = new ConcurrentHashMap<>();
    private final Map<Integer, List<Slot>> pending = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, String>> pushedHashes = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<Void>> activeFlushes = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    private record Cached(byte[] bytes, String hash) { }
    private record Slot(String key, byte[] bytes, String hash) { }

    public PlayerPortraitService(MysticCore core, Path cacheDirectory, String template,
            int cacheHours, Consumer<String> available) {
        this.core = core;
        this.cacheDirectory = cacheDirectory;
        this.apiTemplate = template == null || template.isBlank()
                ? "https://hytale.photo/skin/halfbody.png?user={username}" : template.trim();
        this.cacheTtl = Duration.ofHours(Math.max(0, cacheHours));
        this.available = available == null ? ignored -> { } : available;
    }

    public void start() {
        try { Files.createDirectories(cacheDirectory); }
        catch (Exception e) { core.log(Level.WARNING, "[customcontent] Could not create portrait cache: " + e.getMessage()); }
        if (!apiTemplate.contains(TOKEN)) {
            core.log(Level.WARNING, "[customcontent] Portrait API template has no " + TOKEN
                    + " token; every player would resolve to the same image.");
        }
        downloads.execute(this::loadDiskCache);
    }

    public void stop() {
        stopped = true;
        downloads.shutdownNow();
        try { downloads.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        pending.clear();
        pushedHashes.clear();
        activeFlushes.clear();
    }

    /** Returns a UI texture path when cached and queues the corresponding client asset. */
    public String queue(String username, String slotKey, PacketHandler handler) {
        if (stopped || username == null || username.isBlank() || handler == null) return "";
        String safeName = safe(username);
        String safeSlot = safe(slotKey);
        if (safeName.isBlank() || safeSlot.isBlank()) return "";
        Cached cached = cache.get(safeName);
        if (cached == null) {
            download(username.trim(), safeName);
            return "";
        }
        int handlerId = System.identityHashCode(handler);
        String existing = pushedHashes.computeIfAbsent(handlerId, ignored -> new ConcurrentHashMap<>()).get(safeSlot);
        if (!cached.hash.equals(existing)) {
            pending.computeIfAbsent(handlerId, ignored -> Collections.synchronizedList(new ArrayList<>()))
                    .add(new Slot(safeSlot, cached.bytes, cached.hash));
        }
        return UI_PREFIX + safeSlot + ".png";
    }

    /** Flushes queued assets in-order and rebuilds the client's common-asset index once. */
    public CompletableFuture<Void> flush(PacketHandler handler) {
        if (handler == null) return CompletableFuture.completedFuture(null);
        int handlerId = System.identityHashCode(handler);
        List<Slot> queued = pending.remove(handlerId);
        if (queued == null || queued.isEmpty()) return CompletableFuture.completedFuture(null);
        List<Slot> snapshot;
        synchronized (queued) { snapshot = List.copyOf(queued); }
        CompletableFuture<Void> previous = activeFlushes.getOrDefault(handlerId, CompletableFuture.completedFuture(null));
        CompletableFuture<Void> flush = previous.thenRunAsync(() -> push(handler, handlerId, snapshot), downloads);
        activeFlushes.put(handlerId, flush);
        flush.whenComplete((ignored, error) -> activeFlushes.remove(handlerId, flush));
        return flush;
    }

    public void handlerClosed(PacketHandler handler) {
        if (handler == null) return;
        int id = System.identityHashCode(handler);
        pending.remove(id);
        pushedHashes.remove(id);
        activeFlushes.remove(id);
    }

    private void push(PacketHandler handler, int handlerId, List<Slot> slots) {
        Map<String, String> hashes = pushedHashes.computeIfAbsent(handlerId, ignored -> new ConcurrentHashMap<>());
        int count = 0;
        try {
            for (Slot slot : slots) {
                if (slot.hash.equals(hashes.get(slot.key))) continue;
                byte[] bytes = slot.bytes;
                byte[][] parts = ArrayUtil.split(bytes, 0x280000);
                CommonAsset asset = new CommonAsset(ASSET_PREFIX + slot.key + ".png", slot.hash, bytes) {
                    @Override protected CompletableFuture<byte[]> getBlob0() { return CompletableFuture.completedFuture(bytes); }
                };
                Packet[] packets = new Packet[parts.length + 2];
                packets[0] = new AssetInitialize(asset.toPacket(), bytes.length);
                for (int index = 0; index < parts.length; index++) packets[index + 1] = new AssetPart(parts[index]);
                packets[packets.length - 1] = new AssetFinalize();
                for (Packet packet : packets) handler.write((ToClientPacket) packet);
                hashes.put(slot.key, slot.hash);
                count++;
            }
            if (count > 0) handler.writeNoCache((ToClientPacket) new RequestCommonAssetsRebuild());
        } catch (Exception e) {
            core.log(Level.WARNING, "[customcontent] Could not push player portrait assets: " + e.getMessage());
        }
    }

    private void download(String username, String safeName) {
        if (failed(username)) return;
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (inFlight.putIfAbsent(safeName, future) != null) return;
        downloads.execute(() -> {
            try {
                URI uri = uri(username);
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300
                        || !validPng(response.body())) {
                    failures.put(username, Instant.now().plus(FAILED_TTL));
                    return;
                }
                byte[] bytes = response.body();
                Files.createDirectories(cacheDirectory);
                Files.write(cacheDirectory.resolve(safeName + ".png"), bytes);
                cache.put(safeName, new Cached(bytes, hash(bytes)));
                available.accept(username);
            } catch (Exception e) {
                failures.put(username, Instant.now().plus(FAILED_TTL));
            } finally {
                future.complete(null);
                inFlight.remove(safeName, future);
            }
        });
    }

    private void loadDiskCache() {
        if (!Files.isDirectory(cacheDirectory)) return;
        boolean expires = !cacheTtl.isZero();
        Instant cutoff = expires ? Instant.now().minus(cacheTtl) : Instant.MIN;
        try (var files = Files.list(cacheDirectory)) {
            for (Path path : files.filter(file -> file.getFileName().toString().endsWith(".png")).toList()) {
                try {
                    if (expires && Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                        continue;
                    }
                    byte[] bytes = Files.readAllBytes(path);
                    String filename = path.getFileName().toString();
                    if (validPng(bytes)) {
                        cache.put(filename.substring(0, filename.length() - 4), new Cached(bytes, hash(bytes)));
                    } else {
                        Files.deleteIfExists(path);
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            core.log(Level.WARNING, "[customcontent] Could not read player portrait cache: " + e.getMessage());
        }
    }

    private URI uri(String username) {
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
        URI uri = URI.create(apiTemplate.replace(TOKEN, encoded));
        if (!uri.getScheme().equalsIgnoreCase("https") && !uri.getScheme().equalsIgnoreCase("http")) {
            throw new IllegalArgumentException("Portrait endpoint must use HTTP(S)");
        }
        return uri;
    }

    private boolean failed(String username) {
        Instant expiry = failures.get(username);
        if (expiry == null) return false;
        if (Instant.now().isAfter(expiry)) { failures.remove(username, expiry); return false; }
        return true;
    }

    private static String safe(String value) { return value.trim().replaceAll("[^A-Za-z0-9_-]", "_"); }
    private static boolean validPng(byte[] bytes) {
        return bytes != null && bytes.length >= 8 && bytes.length <= MAX_IMAGE_BYTES
                && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a;
    }
    private static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder output = new StringBuilder(64);
        for (byte value : digest) output.append(String.format("%02x", value & 0xff));
        return output.toString();
    }
}
