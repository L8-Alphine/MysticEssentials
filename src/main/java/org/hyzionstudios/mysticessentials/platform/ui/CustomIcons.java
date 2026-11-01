package org.hyzionstudios.mysticessentials.platform.ui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
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

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.asset.FileCommonAsset;
import com.hypixel.hytale.server.core.universe.Universe;

/**
 * Server-supplied icons: every PNG in {@code mods/MysticEssentials/icons/} is
 * registered as a common asset under {@code UI/Custom/MysticEssentials/CustomIcons/}
 * and referenced anywhere an icon is accepted as {@code custom:<name>} (the
 * file name without {@code .png}).
 *
 * <p>Registered through {@code CommonAssetModule#addCommonAsset}, the instance
 * method: the static registry alone records the asset but never sends it, so
 * players already online would see nothing. Players who join later receive it
 * with the rest of the common assets. The engine toasts every online player
 * when an asset is added or changed, so a reload only touches files whose
 * bytes changed — an unchanged icon is skipped before it reaches the engine.</p>
 *
 * <p>Loaded at startup, before anyone can join, so every client has them by
 * the time a page or the rail asks for one.</p>
 */
public final class CustomIcons {

    /** The asset pack the icons are registered under: this mod's own. */
    static final String PACK = "org.hyzionstudios:MysticEssentials";
    static final String ASSET_DIR = "UI/Custom/MysticEssentials/CustomIcons/";
    static final String PREFIX = "custom:";
    private static final int MAX_BYTES = 1 << 20;
    private static final int MAX_ICONS = 256;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    /** Name (lowercase) to asset path, swapped whole on each load. */
    private static volatile Map<String, String> paths = Map.of();
    /** Asset path to content hash, so a reload re-registers only what changed. */
    private static final Map<String, String> hashes = new LinkedHashMap<>();

    private CustomIcons() {
    }

    /**
     * Scans the icons folder and registers what is new or changed. Reads
     * files, so call it from startup or the scheduler, never a world thread.
     */
    public static synchronized void load(MysticCore core, Path folder) {
        Map<String, String> next = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        int changed = 0;
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            core.log(Level.WARNING, "Custom icons: cannot create " + folder + ": " + e.getMessage());
            return;
        }
        CommonAssetModule assets = CommonAssetModule.get();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.png")) {
            for (Path file : files) {
                if (next.size() >= MAX_ICONS) {
                    problems.add("more than " + MAX_ICONS + " icons; the rest were skipped");
                    break;
                }
                String name = nameOf(file);
                if (name.isEmpty()) {
                    problems.add(file.getFileName() + ": use letters, digits, - or _ in the name");
                    continue;
                }
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > MAX_BYTES || !isPng(bytes)) {
                    problems.add(file.getFileName() + (bytes.length > MAX_BYTES ? ": over 1 MB" : ": not a PNG"));
                    continue;
                }
                String path = ASSET_DIR + name + ".png";
                next.put(name.toLowerCase(Locale.ROOT), path);
                String hash = sha256(bytes);
                if (hash.equals(hashes.get(path)) || assets == null) {
                    continue;
                }
                assets.addCommonAsset(PACK, new FileCommonAsset(file, path, bytes), false);
                hashes.put(path, hash);
                changed++;
            }
        } catch (IOException e) {
            core.log(Level.WARNING, "Custom icons: could not read " + folder + ": " + e.getMessage());
        }
        paths = Map.copyOf(next);
        if (changed > 0 && Universe.get() != null && Universe.get().getPlayerCount() > 0) {
            // addCommonAsset streams the bytes without asking clients to remount.
            Universe.get().broadcastPacketNoCache(new RequestCommonAssetsRebuild());
        }
        if (!next.isEmpty() || !problems.isEmpty()) {
            core.log(Level.INFO, "Custom icons: " + next.size() + " loaded" + (changed > 0 ? " (" + changed + " new or changed)" : "")
                    + (problems.isEmpty() ? "" : "; skipped " + String.join("; ", problems)));
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
        for (Map.Entry<String, String> icon : paths.entrySet()) {
            String path = icon.getValue();
            String file = path.substring(ASSET_DIR.length(), path.length() - ".png".length());
            entries.add(new VanillaIcons.Entry(PREFIX + file, path));
        }
        return entries;
    }

    /** @return {@code custom:<name>} for one of these paths, or empty */
    public static String nameOf(String path) {
        if (path == null || !path.startsWith(ASSET_DIR) || !paths.containsValue(path)) {
            return "";
        }
        return PREFIX + path.substring(ASSET_DIR.length(), path.length() - ".png".length());
    }

    /** File stem when it is a safe asset name, else empty. */
    static String nameOf(Path file) {
        String stem = file.getFileName().toString();
        stem = stem.substring(0, stem.length() - ".png".length());
        return stem.matches("[A-Za-z0-9_-]{1,48}") ? stem : "";
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
    }
}
