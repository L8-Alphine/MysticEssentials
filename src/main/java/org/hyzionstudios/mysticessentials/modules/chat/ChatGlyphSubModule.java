package org.hyzionstudios.mysticessentials.modules.chat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.ResourceCommonAsset;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Replaces friendly tokens and configured raw symbols with custom glyph
 * codepoints. The actual files are shipped as common assets; the font binding is
 * intentionally data-driven so it can be adjusted when Hytale's final text asset
 * format settles.
 */
public final class ChatGlyphSubModule {

    private static final String ASSET_ROOT = "Common/Resources/MysticEssentials/Chat/Glyphs";
    private static final String UNICODE_ROOT = "Common/Resources/MysticEssentials/Chat/Unicode";
    private static final String BUNDLED_CATALOG = "/" + ASSET_ROOT + "/glyphs.json";
    private static final String BUNDLED_EMOJI_SEQUENCES = "/" + UNICODE_ROOT + "/emoji-sequences.json";
    private static final String BUNDLED_UNICODE_POLICY = "/" + UNICODE_ROOT + "/unicode-symbol-policy.json";

    private final MysticCore core;
    private ChatConfig.Glyphs config = new ChatConfig.Glyphs();
    private List<ChatGlyph> glyphs = List.of();
    private Set<String> emojiSequences = Set.of();
    private boolean assetsRegistered;

    public ChatGlyphSubModule(MysticCore core) {
        this.core = core;
    }

    public void reload(ChatConfig.Glyphs config) {
        this.config = config == null ? new ChatConfig.Glyphs() : config;
        ChatGlyphCatalog catalog = loadCatalog();
        glyphs = catalog.glyphs == null ? List.of() : sorted(catalog.glyphs);
        emojiSequences = loadEmojiSequences();
        assetsRegistered = this.config.registerCommonAssets && registerAssets(glyphs);
        copyUnicodePolicy();
    }

    public String apply(UUID player, String raw) {
        PlayerRef ref = core.platform().findPlayer(player).orElse(null);
        return apply(ref, raw);
    }

    public String apply(PlayerRef player, String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String result = Normalizer.normalize(raw, Normalizer.Form.NFC);
        if (config.enabled) {
            for (ChatGlyph glyph : glyphs) {
                String replacement = replacementFor(player, glyph);
                if (glyph.alias != null && !glyph.alias.isBlank()) {
                    result = result.replace(glyph.alias, replacement);
                }
                if (config.allowRawUnicodeSymbols && glyph.symbol != null && !glyph.symbol.isBlank()) {
                    result = result.replace(glyph.symbol, replacement);
                }
            }
        }
        return config.stripUnsafeInvisibleCharacters ? stripUnsafeInvisible(result) : result;
    }

    public List<ChatGlyph> glyphs() {
        return List.copyOf(glyphs);
    }

    public boolean assetsRegistered() {
        return assetsRegistered;
    }

    public int emojiSequenceCount() {
        return emojiSequences.size();
    }

    private String replacementFor(PlayerRef player, ChatGlyph glyph) {
        if (!allowed(player, glyph)) {
            return glyph.fallback == null ? "" : glyph.fallback;
        }
        if (config.emitPrivateUseCodepoints) {
            return glyph.privateUseText();
        }
        return glyph.fallback == null ? glyph.alias : glyph.fallback;
    }

    private boolean allowed(PlayerRef player, ChatGlyph glyph) {
        if (player == null) {
            return true;
        }
        String permission = glyph.permission;
        if ((permission == null || permission.isBlank()) && config.permissions != null) {
            permission = config.permissions.get(glyph.category == null ? "custom" : glyph.category);
        }
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    private ChatGlyphCatalog loadCatalog() {
        Path file = core.paths().moduleExtraConfigFile("chat", "glyphs.json");
        try {
            JsonElement loaded = Json.readFile(file);
            if (loaded != null) {
                return Json.fromJson(loaded, ChatGlyphCatalog.class);
            }
            JsonElement bundled = bundledCatalog();
            Json.writeFile(file, bundled);
            core.log(Level.INFO, "Generated default modules/chat/glyphs.json");
            return Json.fromJson(bundled, ChatGlyphCatalog.class);
        } catch (Exception e) {
            core.log(Level.WARNING, "Failed to load chat glyph catalog: " + e.getMessage());
            return new ChatGlyphCatalog();
        }
    }

    private JsonElement bundledCatalog() {
        try (InputStream in = ChatGlyphSubModule.class.getResourceAsStream(BUNDLED_CATALOG)) {
            if (in == null) {
                return Json.toTree(new ChatGlyphCatalog());
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader);
            }
        } catch (Exception e) {
            core.log(Level.WARNING, "Failed to read bundled chat glyph catalog: " + e.getMessage());
            return Json.toTree(new ChatGlyphCatalog());
        }
    }

    private Set<String> loadEmojiSequences() {
        Path file = core.paths().moduleExtraConfigFile("chat", "emoji-sequences.json");
        try {
            JsonElement loaded = Json.readFile(file);
            if (loaded == null) {
                loaded = readBundledJson(BUNDLED_EMOJI_SEQUENCES);
                Json.writeFile(file, loaded);
                core.log(Level.INFO, "Generated default modules/chat/emoji-sequences.json");
            }
            JsonObject object = Json.asObject(loaded);
            Set<String> sequences = new HashSet<>();
            if (object.has("entries") && object.get("entries").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("entries")) {
                    JsonObject entry = Json.asObject(element);
                    if (entry.has("sample")) {
                        sequences.add(entry.get("sample").getAsString());
                    }
                }
            }
            return Set.copyOf(sequences);
        } catch (Exception e) {
            core.log(Level.WARNING, "Failed to load chat emoji sequence catalog: " + e.getMessage());
            return Set.of();
        }
    }

    private void copyUnicodePolicy() {
        Path file = core.paths().moduleExtraConfigFile("chat", "unicode-symbol-policy.json");
        try {
            if (Json.readFile(file) == null) {
                Json.writeFile(file, readBundledJson(BUNDLED_UNICODE_POLICY));
                core.log(Level.INFO, "Generated default modules/chat/unicode-symbol-policy.json");
            }
        } catch (Exception e) {
            core.log(Level.WARNING, "Failed to generate chat unicode symbol policy: " + e.getMessage());
        }
    }

    private JsonElement readBundledJson(String resourcePath) {
        try (InputStream in = ChatGlyphSubModule.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return new JsonObject();
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader);
            }
        } catch (Exception e) {
            core.log(Level.WARNING, "Failed to read bundled JSON " + resourcePath + ": " + e.getMessage());
            return new JsonObject();
        }
    }

    private boolean registerAssets(List<ChatGlyph> definitions) {
        boolean any = false;
        for (ChatGlyph glyph : definitions) {
            if (glyph.asset == null || glyph.asset.isBlank()) {
                continue;
            }
            String resourcePath = ASSET_ROOT + "/" + glyph.asset;
            String assetName = resourcePath;
            try {
                try (InputStream in = ChatGlyphSubModule.class.getResourceAsStream("/" + resourcePath)) {
                    if (in == null) {
                        core.log(Level.WARNING, "Missing bundled glyph asset: " + resourcePath);
                        continue;
                    }
                }
                // ResourceCommonAsset.of resolves the resource from its SECOND argument
                // via Class.getResourceAsStream, so it must be the absolute "/..." form;
                // the third argument is only stored as the asset's path.
                ResourceCommonAsset asset = ResourceCommonAsset.of(ChatGlyphSubModule.class,
                        "/" + resourcePath, resourcePath);
                if (asset == null) {
                    core.log(Level.WARNING, "Missing bundled glyph asset: " + resourcePath);
                    continue;
                }
                CommonAssetRegistry.addCommonAsset(assetName, asset);
                any = true;
            } catch (Throwable t) {
                core.log(Level.WARNING, "Could not register glyph common asset '" + assetName + "': " + t);
            }
        }
        return any;
    }

    private static List<ChatGlyph> sorted(List<ChatGlyph> definitions) {
        List<ChatGlyph> copy = new ArrayList<>(definitions);
        copy.sort(Comparator
                .comparingInt((ChatGlyph glyph) -> Math.max(length(glyph.alias), length(glyph.symbol)))
                .reversed());
        return copy;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static String stripUnsafeInvisible(String input) {
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length();) {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (Character.isISOControl(cp)) {
                continue;
            }
            if (type == Character.FORMAT && !isEmojiFormatControl(cp) && (cp < 0xE000 || cp > 0xF8FF)) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static boolean isEmojiFormatControl(int cp) {
        return cp == 0x200D // zero-width joiner for emoji sequences
                || cp == 0xFE0E // text presentation selector
                || cp == 0xFE0F // emoji presentation selector
                || (cp >= 0xE0020 && cp <= 0xE007F); // emoji tag sequences, e.g. subdivision flags
    }
}
