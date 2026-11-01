package org.hyzionstudios.mysticessentials.platform.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Stream;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.asset.FileCommonAsset;
import com.hypixel.hytale.server.core.universe.Universe;

/**
 * Server-supplied icons and textures: every PNG under
 * {@code mods/MysticEssentials/icons/} (subfolders too) becomes usable anywhere
 * an icon is accepted as {@code custom:<name>} — the path inside the folder
 * without {@code .png}, so {@code icons/models/dragon.png} is
 * {@code custom:models/dragon}.
 *
 * <p><b>The folder belongs to the server owner.</b> It is only ever read: the
 * mod never creates, rewrites or deletes anything in it. Each PNG is passed
 * through {@link IconOptimizer} and the result kept in the mod's own cache
 * ({@code data/cache/icons/}), which is what clients are sent. A file is only
 * re-optimized when its bytes change.</p>
 *
 * <p>Registered through {@code CommonAssetModule#addCommonAsset}, the instance
 * method: the static registry alone records the asset but never sends it, so
 * players already online would see nothing. Players who join later receive it
 * with the rest of the common assets. The engine toasts every online player
 * when an asset is added or changed, so a reload only touches files whose
 * bytes changed — an unchanged icon is skipped before it reaches the engine.</p>
 */
public final class CustomIcons {

    /** The asset pack the icons are registered under: this mod's own. */
    static final String PACK = "org.hyzionstudios:MysticEssentials";
    static final String ASSET_DIR = "UI/Custom/MysticEssentials/CustomIcons/";
    static final String PREFIX = "custom:";
    /** A source larger than this is skipped: it is a texture sheet, not an icon. */
    private static final long MAX_SOURCE_BYTES = 32L << 20;
    private static final int MAX_ICONS = 512;
    private static final int MAX_DEPTH = 4;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    /** Name (lowercase) to asset path, swapped whole on each load. */
    private static volatile Map<String, String> paths = Map.of();
    /** Asset path to the hash of what was registered, so a reload re-registers only what changed. */
    private static final Map<String, String> registered = new LinkedHashMap<>();
    /** Names, sizes and times of the folder's files at the last load, for {@link #refreshIfChanged}. */
    private static volatile String lastSnapshot = "";

    private CustomIcons() {
    }

    /**
     * Scans the icons folder, optimizes what is new or changed into the cache,
     * and registers it. Reads and writes files, so call it from startup or the
     * scheduler, never a world thread.
     *
     * @param folder the server owner's folder (read only)
     * @param cache  the mod's cache for optimized copies
     */
    public static synchronized void load(MysticCore core, Path folder, Path cache) {
        lastSnapshot = snapshot(folder);
        Map<String, String> next = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        int changed = 0;
        int optimized = 0;
        long sourceBytes = 0;
        long shippedBytes = 0;
        if (!Files.isDirectory(folder)) {
            paths = Map.of();
            TextureIndex.invalidate();
            return;
        }
        JsonObject index = readIndex(cache);
        JsonObject nextIndex = new JsonObject();
        CommonAssetModule assets = CommonAssetModule.get();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(folder, MAX_DEPTH)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            core.log(Level.WARNING, "Custom icons: could not read " + folder + ": " + e.getMessage());
            return;
        }
        for (Path file : files) {
            if (next.size() >= MAX_ICONS) {
                problems.add("more than " + MAX_ICONS + " files; the rest were skipped");
                break;
            }
            String name = nameOf(folder, file);
            if (name.isEmpty()) {
                problems.add(folder.relativize(file) + ": use letters, digits, - or _ in names");
                continue;
            }
            try {
                if (Files.size(file) > MAX_SOURCE_BYTES) {
                    problems.add(name + ": over 32 MB");
                    continue;
                }
                byte[] source = Files.readAllBytes(file);
                if (!isPng(source)) {
                    problems.add(name + ": not a PNG");
                    continue;
                }
                String sourceHash = sha256(source);
                Path cached = cache.resolve(name + ".png");
                byte[] shipped = cachedCopy(index, name, sourceHash, cached);
                if (shipped == null) {
                    IconOptimizer.Result result = IconOptimizer.optimize(source);
                    shipped = result.bytes();
                    Files.createDirectories(cached.getParent());
                    Files.write(cached, shipped);
                    if (result.changed()) {
                        optimized++;
                    }
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("source", sourceHash);
                nextIndex.add(name, entry);
                sourceBytes += source.length;
                shippedBytes += shipped.length;

                String path = ASSET_DIR + name + ".png";
                next.put(name.toLowerCase(Locale.ROOT), path);
                String hash = sha256(shipped);
                if (hash.equals(registered.get(path)) || assets == null) {
                    continue;
                }
                assets.addCommonAsset(PACK, new FileCommonAsset(cached, path, shipped), false);
                registered.put(path, hash);
                changed++;
            } catch (IOException | RuntimeException e) {
                problems.add(name + ": " + e.getMessage());
            }
        }
        writeIndex(core, cache, nextIndex);
        paths = Map.copyOf(next);
        TextureIndex.invalidate();
        if (changed > 0 && Universe.get() != null && Universe.get().getPlayerCount() > 0) {
            // addCommonAsset streams the bytes without asking clients to remount.
            Universe.get().broadcastPacketNoCache(new RequestCommonAssetsRebuild());
        }
        if (!next.isEmpty() || !problems.isEmpty()) {
            core.log(Level.INFO, "Custom icons: " + next.size() + " loaded"
                    + (optimized > 0 ? ", " + optimized + " optimized" : "")
                    + (sourceBytes > 0 ? " (" + kb(sourceBytes) + " in the folder, " + kb(shippedBytes) + " sent to players)" : "")
                    + (problems.isEmpty() ? "" : "; skipped " + String.join("; ", problems)));
        }
    }

    /**
     * Loads again only when a file in the folder was added, removed or
     * changed — judged by names, sizes and times, so an idle check reads no
     * file. Runs on the scheduler every few seconds.
     */
    public static void refreshIfChanged(MysticCore core, Path folder, Path cache) {
        if (!snapshot(folder).equals(lastSnapshot)) {
            load(core, folder, cache);
        }
    }

    private static String snapshot(Path folder) {
        if (!Files.isDirectory(folder)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try (Stream<Path> walk = Files.walk(folder, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile).sorted().forEach(file -> {
                try {
                    out.append(file).append('|').append(Files.size(file)).append('|')
                            .append(Files.getLastModifiedTime(file).toMillis()).append(';');
                } catch (IOException ignored) {
                    out.append(file).append("|?;");
                }
            });
        } catch (IOException e) {
            return "";
        }
        return out.toString();
    }

    /** The cached optimized copy when its source has not changed since, else {@code null}. */
    private static byte[] cachedCopy(JsonObject index, String name, String sourceHash, Path cached) {
        try {
            JsonElement entry = index.get(name);
            if (entry == null || !entry.isJsonObject()
                    || !sourceHash.equals(entry.getAsJsonObject().get("source").getAsString())
                    || !Files.isRegularFile(cached)) {
                return null;
            }
            return Files.readAllBytes(cached);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static JsonObject readIndex(Path cache) {
        try {
            return Json.asObject(Json.readFile(cache.resolve("index.json")));
        } catch (IOException | RuntimeException e) {
            return new JsonObject();
        }
    }

    private static void writeIndex(MysticCore core, Path cache, JsonObject index) {
        try {
            Files.createDirectories(cache);
            Json.writeFile(cache.resolve("index.json"), index);
        } catch (IOException e) {
            core.log(Level.WARNING, "Custom icons: could not write the cache index: " + e.getMessage());
        }
    }

    /** @return the asset path for {@code custom:<name>} (or a bare name), or empty when there is none */
    public static String resolve(String reference) {
        if (reference == null) {
            return "";
        }
        String name = reference.trim();
        if (name.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            name = name.substring(PREFIX.length()).trim();
        }
        return paths.getOrDefault(name.toLowerCase(Locale.ROOT), "");
    }

    /** @return the loaded icons as picker entries ({@code custom:<name>} to path), in folder order */
    public static List<VanillaIcons.Entry> entries() {
        List<VanillaIcons.Entry> entries = new ArrayList<>();
        for (String path : paths.values()) {
            entries.add(new VanillaIcons.Entry(PREFIX + path.substring(ASSET_DIR.length(), path.length() - 4), path));
        }
        return entries;
    }

    /** @return {@code custom:<name>} for one of these paths, or empty */
    public static String nameOf(String path) {
        if (path == null || !path.startsWith(ASSET_DIR) || !paths.containsValue(path)) {
            return "";
        }
        return PREFIX + path.substring(ASSET_DIR.length(), path.length() - 4);
    }

    /** The path inside the folder without {@code .png}, when every part is a safe asset name; else empty. */
    static String nameOf(Path folder, Path file) {
        String relative = folder.relativize(file).toString().replace('\\', '/');
        relative = relative.substring(0, relative.length() - 4);
        for (String part : relative.split("/")) {
            if (!part.matches("[A-Za-z0-9_-]{1,48}")) {
                return "";
            }
        }
        return relative;
    }

    static boolean isPng(byte[] bytes) {
        if (bytes.length < PNG_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (bytes[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static String kb(long bytes) {
        return bytes < 1024 ? bytes + " B" : (bytes / 1024) + " KB";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
    }
}
