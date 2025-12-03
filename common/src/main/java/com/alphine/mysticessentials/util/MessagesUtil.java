package com.alphine.mysticessentials.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message service with:
 * - Per-locale files under /config/mysticessentials/messages/<locale>.json
 * - Bundled defaults under /assets/mysticessentials/lang/<locale>.json inside the jar
 * - Placeholder replacement {name}
 * - Legacy color codes (&0..&f, &l, &o, &n, &m, &r) → Style on Components
 *
 * Usage:
 *   MessagesUtil.init(configDir, activeLocale); // once at startup
 *   Component c = MessagesUtil.msg("homes.set", Map.of("home", "spawn"));
 *   source.sendSuccess(() -> c, false);
 */
public final class MessagesUtil {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>(){}.getType();

    private static final String MOD_ID = "mysticessentials";
    private static final String BUNDLED_PATH_FMT = "/assets/" + MOD_ID + "/lang/%s.json";

    private static Path messagesDir;
    private static String activeLocale = "en_us";

    // Resolution chain (in order): userActive → bundledActive → user_en_us → bundled_en_us
    private static Map<String, String> userActive = Map.of();
    private static Map<String, String> bundledActive = Map.of();
    private static Map<String, String> userEnUs = Map.of();
    private static Map<String, String> bundledEnUs = Map.of();

    private static boolean initialized = false;

    private MessagesUtil() {}

    public static void init(Path configDir, String localeCode) {
        messagesDir = configDir.resolve("messages");
        activeLocale = normalizeLocale(localeCode);
        try {
            Files.createDirectories(messagesDir);
        } catch (Exception e) {
            System.err.println("[" + MOD_ID + "] Failed to create messages directory: " + e.getMessage());
        }

        // Ensure default files exist in /config by copying from bundled resources if missing.
        ensureFilePresent("en_us");
        ensureFilePresent(activeLocale);

        // Load resolution chain
        bundledEnUs = loadBundled("en_us");
        userEnUs    = loadUser("en_us");

        bundledActive = activeLocale.equals("en_us") ? bundledEnUs : loadBundled(activeLocale);
        userActive    = activeLocale.equals("en_us") ? userEnUs    : loadUser(activeLocale);

        initialized = true;
    }

    public static void reload() {
        if (!initialized) return;
        init(messagesDir.getParent(), activeLocale);
    }

    /**
     * Get a Component for the given key with placeholder map.
     */
    public static Component msg(String key, Map<String, ?> placeholders) {
        String raw = getRaw(key);
        if (raw == null) raw = key; // show key if truly missing
        raw = applyPlaceholders(raw, placeholders);
        return legacyStringToComponent(raw);
    }

    /**
     * Convenience overload: no placeholders.
     */
    public static Component msg(String key) {
        return msg(key, Collections.emptyMap());
    }

    /**
     * Resolve the raw message string using the fallback chain.
     */
    public static String getRaw(String key) {
        if (!initialized) return key;

        // Lookups in order of precedence:
        String v;
        if ((v = userActive.get(key)) != null) return v;
        if ((v = bundledActive.get(key)) != null) return v;
        if ((v = userEnUs.get(key)) != null) return v;
        if ((v = bundledEnUs.get(key)) != null) return v;
        return null;
    }

    // ---------- internals ----------

    private static String normalizeLocale(String in) {
        if (in == null || in.isBlank()) return "en_us";
        // Accept both en_US / es-ES and convert to Mojang-style snake: en_us, es_es
        String s = in.replace('-', '_');
        String[] parts = s.split("_", 2);
        if (parts.length == 2) {
            return parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toLowerCase(Locale.ROOT);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static void ensureFilePresent(String locale) {
        Path file = messagesDir.resolve(locale + ".json");
        if (Files.exists(file)) return;
        Map<String, String> bundled = loadBundled(locale);
        if (bundled.isEmpty()) {
            // If we don't have that locale bundled, fall back to en_us.
            bundled = loadBundled("en_us");
            if (bundled.isEmpty()) return;
        }
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(bundled, w);
        } catch (Exception e) {
            System.err.println("[" + MOD_ID + "] Failed to write default messages for " + locale + ": " + e.getMessage());
        }
    }

    private static Map<String, String> loadUser(String locale) {
        Path file = messagesDir.resolve(locale + ".json");
        if (!Files.exists(file)) return Map.of();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String,String> map = GSON.fromJson(r, MAP_TYPE);
            return map != null ? map : Map.of();
        } catch (Exception e) {
            System.err.println("[" + MOD_ID + "] Failed to load user messages for " + locale + ": " + e.getMessage());
            return Map.of();
        }
    }

    private static Map<String, String> loadBundled(String locale) {
        String path = BUNDLED_PATH_FMT.formatted(locale);
        try (InputStream in = MessagesUtil.class.getResourceAsStream(path)) {
            if (in == null) return Map.of();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                Map<String,String> map = GSON.fromJson(br, MAP_TYPE);
                return map != null ? map : Map.of();
            }
        } catch (Exception e) {
            System.err.println("[" + MOD_ID + "] Failed to load bundled messages for " + locale + ": " + e.getMessage());
            return Map.of();
        }
    }

    private static String applyPlaceholders(String raw, Map<String, ?> ph) {
        if (ph == null || ph.isEmpty()) return raw;
        String out = raw;
        for (Map.Entry<String, ?> e : ph.entrySet()) {
            String k = "\\{" + Pattern.quote(e.getKey()) + "\\}";
            out = out.replaceAll(k, Matcher.quoteReplacement(String.valueOf(e.getValue())));
        }
        return out;
    }

    // --- Legacy color/format parser: &0..&f, &l, &o, &n, &m, &r ---

    private static final Pattern CODE = Pattern.compile("&([0-9a-fA-FlLoOnNmMrR])");

    private static Component legacyStringToComponent(String s) {
        if (s == null || s.isEmpty()) return Component.empty();

        Matcher m = CODE.matcher(s);
        int lastEnd = 0;

        Style current = Style.EMPTY;
        MutableComponent root = Component.empty();

        while (m.find()) {
            // Text before the code
            if (m.start() > lastEnd) {
                String chunk = s.substring(lastEnd, m.start());
                if (!chunk.isEmpty()) root.append(Component.literal(chunk).withStyle(current));
            }

            char code = Character.toLowerCase(m.group(1).charAt(0));
            switch (code) {
                // Colors
                case '0' -> current = current.withColor(ChatFormatting.BLACK);
                case '1' -> current = current.withColor(ChatFormatting.DARK_BLUE);
                case '2' -> current = current.withColor(ChatFormatting.DARK_GREEN);
                case '3' -> current = current.withColor(ChatFormatting.DARK_AQUA);
                case '4' -> current = current.withColor(ChatFormatting.DARK_RED);
                case '5' -> current = current.withColor(ChatFormatting.DARK_PURPLE);
                case '6' -> current = current.withColor(ChatFormatting.GOLD);
                case '7' -> current = current.withColor(ChatFormatting.GRAY);
                case '8' -> current = current.withColor(ChatFormatting.DARK_GRAY);
                case '9' -> current = current.withColor(ChatFormatting.BLUE);
                case 'a' -> current = current.withColor(ChatFormatting.GREEN);
                case 'b' -> current = current.withColor(ChatFormatting.AQUA);
                case 'c' -> current = current.withColor(ChatFormatting.RED);
                case 'd' -> current = current.withColor(ChatFormatting.LIGHT_PURPLE);
                case 'e' -> current = current.withColor(ChatFormatting.YELLOW);
                case 'f' -> current = current.withColor(ChatFormatting.WHITE);

                // Formats
                case 'l' -> current = current.withBold(true);
                case 'o' -> current = current.withItalic(true);
                case 'n' -> current = current.withUnderlined(true);
                case 'm' -> current = current.withStrikethrough(true);

                // Reset
                case 'r' -> current = Style.EMPTY;

                default -> { /* ignore */ }
            }

            lastEnd = m.end();
        }

        // Remainder text
        if (lastEnd < s.length()) {
            String tail = s.substring(lastEnd);
            if (!tail.isEmpty()) root.append(Component.literal(tail).withStyle(current));
        }

        return root;
    }
}
