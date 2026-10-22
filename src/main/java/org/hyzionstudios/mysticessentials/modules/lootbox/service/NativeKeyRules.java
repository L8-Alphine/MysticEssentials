package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Native key items, {@code identityMode: REGISTERED_CUSTOM_ASSET} (P3-WP8,
 * spike S1). Such a key is handed out as its own Item asset,
 * {@code MysticLootBox_<Namespace>_<Name>_<hash>}, generated as a child of the
 * base item ({@code {"Parent": "<base>"}}) into the generated pack. It is only
 * cosmetic: a key is still only valid through its signed marker, whatever its
 * item id.
 *
 * <p>The generated document hides the item from the creative library
 * ({@code Variant}, no {@code Categories}); {@code Recipe} and
 * {@code BlockType} are not inherited by a child Item, so the copy can't be
 * crafted. Base items that place blocks are refused (the child would lose its
 * block). The server reports a native id only once the asset is loaded
 * (LB-006); until then, and whenever the switch {@code keys.nativeKeyItems} is
 * off, keys use the base item. Engine-free.</p>
 */
public final class NativeKeyRules {

    public static final String METADATA_TAG = "METADATA_TAG";
    public static final String REGISTERED_CUSTOM_ASSET = "REGISTERED_CUSTOM_ASSET";
    /** The Item store's folder inside a pack, and our subfolder. */
    public static final String ITEM_STORE_PATH = "Server/Item/Items";
    public static final String ITEM_FOLDER = "MysticLootBox";

    private NativeKeyRules() {
    }

    /** {@code METADATA_TAG} (default) or {@code REGISTERED_CUSTOM_ASSET}. */
    public static String mode(KeyDraft spec) {
        return spec != null && spec.identityMode != null
                && REGISTERED_CUSTOM_ASSET.equalsIgnoreCase(spec.identityMode.trim()) ? REGISTERED_CUSTOM_ASSET
                        : METADATA_TAG;
    }

    public static boolean wantsNative(KeyDraft spec) {
        return REGISTERED_CUSTOM_ASSET.equals(mode(spec));
    }

    /** Publish check of {@code identityMode}; empty when fine. */
    public static String validate(KeyDraft spec) {
        if (spec.identityMode == null || spec.identityMode.isBlank()) {
            return null;
        }
        String mode = spec.identityMode.trim().toUpperCase(Locale.ROOT);
        return mode.equals(METADATA_TAG) || mode.equals(REGISTERED_CUSTOM_ASSET) ? null
                : "identityMode must be METADATA_TAG or REGISTERED_CUSTOM_ASSET";
    }

    /**
     * The generated item id for key {@code namespace:name}: readable segments
     * plus 6 hex of the key id's SHA-256, so {@code a_b:c} and {@code a:b_c}
     * never share an item.
     */
    public static String itemId(String keyId) {
        StringBuilder id = new StringBuilder("MysticLootBox");
        for (String segment : keyId.toLowerCase(Locale.ROOT).split("[:_]")) {
            if (!segment.isEmpty()) {
                id.append('_').append(Character.toUpperCase(segment.charAt(0))).append(segment.substring(1));
            }
        }
        return id.append('_').append(shortHash(keyId)).toString();
    }

    /** The generated Item document. */
    public static String document(String baseItemId) {
        JsonObject doc = new JsonObject();
        doc.addProperty("Parent", baseItemId);
        doc.add("Categories", new JsonArray());
        doc.addProperty("Variant", true);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(doc) + "\n";
    }

    private static String shortHash(String keyId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyId.getBytes(StandardCharsets.UTF_8));
            return String.format(Locale.ROOT, "%02x%02x%02x", digest[0], digest[1], digest[2]);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
