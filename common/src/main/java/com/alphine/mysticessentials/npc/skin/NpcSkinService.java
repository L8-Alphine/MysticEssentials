package com.alphine.mysticessentials.npc.skin;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.npc.model.NpcDefinition;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class NpcSkinService {

    private static final Gson GSON = new Gson();

    public record ResolvedSkin(String value, String signature) {}

    private static final class CacheEntry {
        String value;
        String signature;
        long expiresAt; // epoch millis
    }

    private final MysticEssentialsCommon common;
    private final Path cacheDir;
    private final long ttlMillis;
    private final long negativeTtlMillis;
    private final String mineSkinApiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean allowMojang;
    private final boolean allowUrl;

    // in-memory cache (key -> entry)
    private final Map<String, CacheEntry> cache = new HashMap<>();

    public NpcSkinService(MysticEssentialsCommon common, Path configDir) {
        this.common = common;
        var cfg = common.cfg.npcs.skin;

        this.cacheDir = configDir.resolve(cfg.cacheDir);
        this.ttlMillis = cfg.cacheTtlMinutes * 60L * 1000L;
        this.negativeTtlMillis = cfg.negativeCacheMinutes * 60L * 1000L;
        this.mineSkinApiKey = cfg.mineSkinApiKey;
        this.connectTimeoutMs = cfg.connectTimeoutMs;
        this.readTimeoutMs = cfg.readTimeoutMs;
        this.allowMojang = cfg.allowMojangFetch;
        this.allowUrl = cfg.allowUrlSkins;

        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            System.err.println("[MysticEssentials] Failed to create skin cache dir: " + e.getMessage());
        }
    }

    // --- public API ---------------------------------------------------------

    /**
     * Resolve a skin for a PLAYER-type NPC profile.
     *
     * @param server MinecraftServer (for logging if needed)
     * @param profile NPC player profile
     * @param viewer optional player for MIRROR_VIEWER
     */
    public ResolvedSkin resolve(MinecraftServer server, NpcDefinition.Type.PlayerProfile profile, ServerPlayer viewer) {
        if (!common.cfg.npcs.skin.enabled) return null;

        String source = profile.skin.source != null ? profile.skin.source : "MOJANG_USER";
        String value = profile.skin.value != null ? profile.skin.value : "";

        try {
            return switch (source.toUpperCase(Locale.ROOT)) {
                case "MIRROR_VIEWER" -> resolveFromViewer(viewer);
                case "MOJANG_USER" -> allowMojang ? resolveFromMojangUsername(value) : null;
                case "URL" -> allowUrl ? resolveFromUrl(value) : null;
                case "DEFAULT_RANDOM" -> resolveRandomFallback();
                default -> null;
            };
        } catch (Exception e) {
            System.err.println("[MysticEssentials] Skin resolve failed (" + source + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Apply a resolved skin to a GameProfile by setting the 'textures' property.
     */
    public static void applyToProfile(GameProfile gp, ResolvedSkin skin) {
        if (gp == null || skin == null) return;
        var props = gp.getProperties();
        props.removeAll("textures");
        props.put("textures", new Property("textures", skin.value(), skin.signature()));
    }

    // --- sources ------------------------------------------------------------

    private ResolvedSkin resolveFromViewer(ServerPlayer viewer) {
        if (viewer == null) return null;
        Collection<Property> props = viewer.getGameProfile().getProperties().get("textures");
        if (props == null || props.isEmpty()) return null;
        Property p = props.iterator().next();
        return new ResolvedSkin(p.value(), p.signature());
    }

    private ResolvedSkin resolveRandomFallback() {
        // For now just "borrow" a random bundled fallback / name list.
        // You can extend this later to rotate through a configured list.
        return null;
    }

    private ResolvedSkin resolveFromMojangUsername(String username) throws IOException {
        if (username == null || username.isBlank()) return null;
        String key = "mojang:" + username.toLowerCase(Locale.ROOT);
        ResolvedSkin cached = getCached(key);
        if (cached != null) return cached;
        if (isNegativeCached(key)) return null;

        // Step 1: username -> UUID
        String uuid = fetchMojangUuid(username);
        if (uuid == null) {
            negativeCache(key);
            return null;
        }

        // Step 2: UUID -> profile + textures
        ResolvedSkin skin = fetchMojangSkin(uuid);
        if (skin == null) {
            negativeCache(key);
            return null;
        }

        putCache(key, skin);
        return skin;
    }

    private ResolvedSkin resolveFromUrl(String skinUrl) throws IOException {
        if (skinUrl == null || skinUrl.isBlank()) return null;
        String key = "url:" + skinUrl;
        ResolvedSkin cached = getCached(key);
        if (cached != null) return cached;
        if (isNegativeCached(key)) return null;

        // Uses MineSkin "generate from URL" endpoint
        ResolvedSkin skin = fetchMineSkinFromUrl(skinUrl);
        if (skin == null) {
            negativeCache(key);
            return null;
        }

        putCache(key, skin);
        return skin;
    }

    // --- Mojang HTTP --------------------------------------------------------

    private String fetchMojangUuid(String username) throws IOException {
        String url = "https://api.mojang.com/users/profiles/minecraft/" + username;
        String json = httpGet(url);
        if (json == null || json.isEmpty()) return null;
        record MojangUser(String id) {}
        MojangUser u = GSON.fromJson(json, MojangUser.class);
        return u != null ? u.id : null;
    }

    private ResolvedSkin fetchMojangSkin(String uuid) throws IOException {
        String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false";
        String json = httpGet(url);
        if (json == null || json.isEmpty()) return null;

        record PropertyDto(String name, String value, String signature) {}
        record ProfileDto(List<PropertyDto> properties) {}

        ProfileDto profile = GSON.fromJson(json, ProfileDto.class);
        if (profile == null || profile.properties == null) return null;
        for (PropertyDto p : profile.properties) {
            if ("textures".equals(p.name)) {
                return new ResolvedSkin(p.value, p.signature);
            }
        }
        return null;
    }

    // --- MineSkin HTTP (URL/Skindex etc) -----------------------------------

    private ResolvedSkin fetchMineSkinFromUrl(String skinUrl) throws IOException {
        // API docs: https://docs.mineskin.org/
        URL url = new URL("https://api.mineskin.org/generate/url");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (!mineSkinApiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + mineSkinApiKey);
        }

        record Request(@SerializedName("url") String url) {}
        String body = GSON.toJson(new Request(skinUrl));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() != 200) {
            conn.disconnect();
            return null;
        }

        String json;
        try (InputStream in = conn.getInputStream()) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }

        // Response has data.texture.value/signature
        record Texture(String value, String signature) {}
        record Data(Texture texture) {}
        record Response(Data data) {}

        Response resp = GSON.fromJson(json, Response.class);
        if (resp == null || resp.data == null || resp.data.texture == null) return null;

        return new ResolvedSkin(resp.data.texture.value, resp.data.texture.signature);
    }

    // --- HTTP helper --------------------------------------------------------

    private String httpGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("GET");

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            return null;
        }

        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    // --- disk/memory cache --------------------------------------------------

    private ResolvedSkin getCached(String key) {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.expiresAt > now && entry.value != null) {
            return new ResolvedSkin(entry.value, entry.signature);
        }
        // disk
        Path file = cacheDir.resolve(safeFileName(key) + ".json");
        if (!Files.exists(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            CacheEntry disk = GSON.fromJson(r, CacheEntry.class);
            if (disk != null && disk.expiresAt > now && disk.value != null) {
                cache.put(key, disk);
                return new ResolvedSkin(disk.value, disk.signature);
            }
        } catch (IOException ignored) {}
        return null;
    }

    private boolean isNegativeCached(String key) {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.expiresAt > now && entry.value == null) return true;
        Path file = cacheDir.resolve(safeFileName(key) + ".json");
        if (!Files.exists(file)) return false;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            CacheEntry disk = GSON.fromJson(r, CacheEntry.class);
            if (disk != null && disk.expiresAt > now && disk.value == null) {
                cache.put(key, disk);
                return true;
            }
        } catch (IOException ignored) {}
        return false;
    }

    private void negativeCache(String key) {
        CacheEntry entry = new CacheEntry();
        entry.value = null;
        entry.signature = null;
        entry.expiresAt = System.currentTimeMillis() + negativeTtlMillis;
        cache.put(key, entry);
        writeCacheFile(key, entry);
    }

    private void putCache(String key, ResolvedSkin skin) {
        CacheEntry entry = new CacheEntry();
        entry.value = skin.value();
        entry.signature = skin.signature();
        entry.expiresAt = System.currentTimeMillis() + ttlMillis;
        cache.put(key, entry);
        writeCacheFile(key, entry);
    }

    private void writeCacheFile(String key, CacheEntry entry) {
        try {
            Path file = cacheDir.resolve(safeFileName(key) + ".json");
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(entry, w);
            }
        } catch (IOException ignored) {}
    }

    private static String safeFileName(String key) {
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
