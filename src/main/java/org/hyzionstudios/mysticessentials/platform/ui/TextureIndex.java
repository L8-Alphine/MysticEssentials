package org.hyzionstudios.mysticessentials.platform.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;

/**
 * Every PNG the client has — the game's icons, item and model renders, model,
 * block and particle textures, the mod's own and the server's — searchable by
 * name, for the wiki editor's image search.
 *
 * <p>Read from the common asset registry, which is exactly what clients are
 * sent, so anything listed here draws. Built on first use and again after the
 * server's icons reload: about twelve thousand paths, a few milliseconds.</p>
 */
public final class TextureIndex {

    /** The image search's filters. */
    public enum Source {
        /** Named icons (the server's, Hyzion's, the game's picks) and the game's other icons. */
        ICONS,
        /** Items, drawn as item slots (searched by {@link ItemCatalog}, not here). */
        ITEMS,
        /** Model renders first, then the textures models, blocks and effects are painted with. */
        MODELS,
        /** Everything. */
        ALL;

        public static Source parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return ICONS;
            }
        }
    }

    private static volatile List<String> paths;

    private TextureIndex() {
    }

    /** Forgets the list; the next search reads the registry again. */
    public static void invalidate() {
        paths = null;
    }

    /**
     * @return up to {@code limit} matches, named ones (picker names) first
     */
    public static List<VanillaIcons.Entry> search(Source source, String query, int limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<VanillaIcons.Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (source == Source.ICONS || source == Source.ALL) {
            for (VanillaIcons.Entry entry : VanillaIcons.pickerWithCustom()) {
                if (out.size() >= limit) {
                    return out;
                }
                if (matches(entry.name(), entry.path(), needle) && seen.add(entry.path())) {
                    out.add(entry);
                }
            }
        }
        List<String> all = all();
        if (source == Source.MODELS) {
            addMatching(out, seen, all, needle, limit, path -> path.startsWith("Icons/ModelsGenerated/"));
            addMatching(out, seen, all, needle, limit, path -> !path.startsWith("Icons/") && !path.startsWith("UI/"));
        } else if (source == Source.ICONS) {
            addMatching(out, seen, all, needle, limit, path -> (path.startsWith("Icons/")
                    && !path.startsWith("Icons/ItemsGenerated/") && !path.startsWith("Icons/ModelsGenerated/"))
                    || path.startsWith("UI/WorldMap/MapMarkers/"));
        } else if (source == Source.ALL) {
            addMatching(out, seen, all, needle, limit, path -> true);
        }
        return out;
    }

    private static void addMatching(List<VanillaIcons.Entry> out, Set<String> seen, List<String> all, String needle,
            int limit, java.util.function.Predicate<String> keep) {
        for (String path : all) {
            if (out.size() >= limit) {
                return;
            }
            if (keep.test(path) && matches(path, path, needle) && seen.add(path)) {
                out.add(new VanillaIcons.Entry(path, path));
            }
        }
    }

    private static boolean matches(String name, String path, String needle) {
        return needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)
                || path.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Every PNG path, sorted; a {@code @2x} file is left out when its 1x twin exists. */
    static List<String> all() {
        List<String> current = paths;
        if (current != null) {
            return current;
        }
        Set<String> names = new HashSet<>();
        for (List<CommonAssetRegistry.PackAsset> versions : CommonAssetRegistry.getAllAssets()) {
            if (versions.isEmpty()) {
                continue;
            }
            String name = versions.getLast().asset().getName();
            if (name.toLowerCase(Locale.ROOT).endsWith(".png")) {
                names.add(name);
            }
        }
        List<String> sorted = new ArrayList<>();
        for (String name : names) {
            if (name.contains("@2x.") && names.contains(name.replace("@2x.", "."))) {
                continue;
            }
            sorted.add(name);
        }
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        current = List.copyOf(sorted);
        paths = current;
        return current;
    }
}
