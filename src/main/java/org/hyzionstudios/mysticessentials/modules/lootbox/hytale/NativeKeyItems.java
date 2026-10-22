package org.hyzionstudios.mysticessentials.modules.lootbox.hytale;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.NativeKeyRules;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;

/**
 * Which keys are handed out as their own generated Item (P3-WP8, see
 * {@link NativeKeyRules}). A key's native id is recorded only after its asset
 * loaded, and {@link #itemFor} checks again that it is still loaded before
 * every key item it builds (LB-006): when it isn't, the key goes out as its base
 * item, which is just as valid (the marker is what counts).
 *
 * <p>Behind {@code keys.nativeKeyItems}, off by default: spike S1 (does the
 * client render an Item registered mid-session?) has not been run in game.</p>
 */
public final class NativeKeyItems {

    private record Native(String itemId, String baseItemId) {
    }

    /** Key id to its loaded native item. Static like {@link KeyItemFactory}; cleared on disable. */
    private static final Map<String, Native> LOADED = new ConcurrentHashMap<>();
    /** Key id to why it has no native item (refused or failed), for {@code /lootbox key list}. */
    private static final Map<String, String> PROBLEMS = new ConcurrentHashMap<>();

    private NativeKeyItems() {
    }

    /**
     * Generates and loads the native items of every published key that asks for
     * one; drops the record of keys that no longer do. Cheap when nothing
     * changed. Asset work only, like {@code startVariants}; runs on enable and on
     * the module's periodic check.
     */
    public static synchronized void sync(Collection<KeyDraft.Published> keys, GeneratedAssetService assets,
            boolean enabled) {
        if (!enabled) {
            LOADED.clear();
            PROBLEMS.clear();
            return;
        }
        Map<String, Boolean> seen = new TreeMap<>();
        for (KeyDraft.Published key : keys) {
            if (key.spec == null || key.spec.id == null || !NativeKeyRules.wantsNative(key.spec)) {
                continue;
            }
            String keyId = key.spec.id;
            seen.put(keyId, true);
            String itemId = NativeKeyRules.itemId(keyId);
            Native current = LOADED.get(keyId);
            if (current != null && current.baseItemId().equals(key.spec.baseItemId)
                    && Item.getAssetMap().getAsset(itemId) != null) {
                continue;
            }
            Item base = Item.getAssetMap().getAsset(key.spec.baseItemId);
            if (base == null) {
                fail(keyId, "base item " + key.spec.baseItemId + " is not loaded");
                continue;
            }
            if (base.hasBlockType()) {
                fail(keyId, "base item " + key.spec.baseItemId + " places a block; native key items need a plain item");
                continue;
            }
            try {
                assets.ensureKeyItem(itemId, key.spec.baseItemId);
                LOADED.put(keyId, new Native(itemId, key.spec.baseItemId));
                PROBLEMS.remove(keyId);
            } catch (IOException | RuntimeException e) {
                fail(keyId, e.getMessage());
            }
        }
        LOADED.keySet().removeIf(keyId -> !seen.containsKey(keyId));
        PROBLEMS.keySet().removeIf(keyId -> !seen.containsKey(keyId));
    }

    public static void clear() {
        LOADED.clear();
        PROBLEMS.clear();
    }

    /** The item id a new key item of {@code definition} uses: its native item when loaded, else the base item. */
    public static String itemFor(KeyDraft.Published definition) {
        KeyDraft spec = definition.spec;
        Native loaded = spec == null || spec.id == null ? null : LOADED.get(spec.id);
        if (loaded != null && loaded.baseItemId().equals(spec.baseItemId)
                && Item.getAssetMap().getAsset(loaded.itemId()) != null) {
            return loaded.itemId();
        }
        return spec == null ? null : spec.baseItemId;
    }

    /** The loaded native item id of {@code keyId}, or {@code null} (LB-006: never one that isn't loaded). */
    public static String customItemId(String keyId) {
        Native loaded = keyId == null ? null : LOADED.get(keyId);
        return loaded != null && Item.getAssetMap().getAsset(loaded.itemId()) != null ? loaded.itemId() : null;
    }

    /** Why {@code keyId} has no native item, or {@code null}. */
    public static String problem(String keyId) {
        return keyId == null ? null : PROBLEMS.get(keyId);
    }

    private static void fail(String keyId, String why) {
        LOADED.remove(keyId);
        PROBLEMS.put(keyId, why == null ? "unknown error" : why);
    }
}
