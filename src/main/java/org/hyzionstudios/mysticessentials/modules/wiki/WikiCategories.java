package org.hyzionstudios.mysticessentials.modules.wiki;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Categories in categories.
 *
 * <p>A category is a path, {@code Mods/Endgame & QoL/Bosses}, so nesting
 * needs no new field on an article or a contributed entry: whoever files a
 * page decides where it sits. The browser draws the paths as a tree that
 * always shows the top level and opens every node on the way to the selected
 * one, and selecting a node lists every page under it.</p>
 *
 * <p>Comparisons ignore case, as single categories always have; a node is
 * shown with the spelling its first page used.</p>
 */
final class WikiCategories {

    static final char SEPARATOR = '/';
    static final String FALLBACK = "General";
    /** Deeper nodes still list and open; they stop indenting here. */
    static final int MAX_INDENT = 3;

    /**
     * One row of the drawn tree.
     *
     * @param count       pages in this category and everything under it
     * @param hasChildren whether it has sub-categories
     * @param open        whether its sub-categories are shown
     */
    record Node(String path, String name, int depth, int count, boolean hasChildren, boolean open,
            boolean selected) {
    }

    private WikiCategories() {
    }

    /** Trims each part and drops empty ones: {@code " Mods // Endgame "} is {@code "Mods/Endgame"}. */
    static String normalize(String category) {
        if (category == null) {
            return FALLBACK;
        }
        StringBuilder out = new StringBuilder();
        for (String part : category.split(String.valueOf(SEPARATOR))) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                out.append(out.length() == 0 ? "" : String.valueOf(SEPARATOR)).append(trimmed);
            }
        }
        return out.length() == 0 ? FALLBACK : out.toString();
    }

    /** Whether {@code category} is {@code node} or anywhere under it. */
    static boolean within(String category, String node) {
        if (category == null || node == null) {
            return false;
        }
        return category.equalsIgnoreCase(node)
                || (category.length() > node.length() + 1
                        && category.charAt(node.length()) == SEPARATOR
                        && category.regionMatches(true, 0, node, 0, node.length()));
    }

    /** The node's own name, the last part of its path. */
    static String leaf(String path) {
        int cut = path.lastIndexOf(SEPARATOR);
        return cut < 0 ? path : path.substring(cut + 1);
    }

    /** A path as a reader sees it: {@code Mods / Endgame & QoL}. */
    static String display(String path) {
        return path == null ? "" : path.replace(String.valueOf(SEPARATOR), " / ");
    }

    /** Every path from the top down to {@code path}, itself last. */
    static List<String> lineage(String path) {
        List<String> out = new ArrayList<>();
        int cut = path.indexOf(SEPARATOR);
        while (cut > 0) {
            out.add(path.substring(0, cut));
            cut = path.indexOf(SEPARATOR, cut + 1);
        }
        out.add(path);
        return out;
    }

    /**
     * The tree as rows, top to bottom.
     *
     * @param ordered        category paths in browser order; siblings keep the order
     *                       their first path has here
     * @param pageCategories one entry per page, for the counts
     * @param selected       the selected path, or {@code null} for all pages
     */
    static List<Node> rows(List<String> ordered, List<String> pageCategories, String selected) {
        Map<String, Integer> counts = new HashMap<>();
        for (String category : pageCategories) {
            for (String path : lineage(category)) {
                counts.merge(lower(path), 1, Integer::sum);
            }
        }
        // Insertion order is the drawing order: a parent is created by its
        // first descendant, at that descendant's place in `ordered`.
        Map<String, String> spelling = new LinkedHashMap<>();
        Map<String, List<String>> children = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (String category : ordered) {
            String parent = null;
            for (String path : lineage(category)) {
                String key = lower(path);
                if (!spelling.containsKey(key)) {
                    spelling.put(key, path);
                    (parent == null ? roots : children.computeIfAbsent(parent, k -> new ArrayList<>())).add(key);
                }
                parent = key;
            }
        }
        List<Node> rows = new ArrayList<>();
        for (String root : roots) {
            walk(root, 0, spelling, children, counts, selected, rows);
        }
        return rows;
    }

    private static void walk(String key, int depth, Map<String, String> spelling, Map<String, List<String>> children,
            Map<String, Integer> counts, String selected, List<Node> rows) {
        String path = spelling.get(key);
        List<String> kids = children.getOrDefault(key, List.of());
        boolean open = selected != null && within(selected, path);
        rows.add(new Node(path, leaf(path), depth, counts.getOrDefault(key, 0), !kids.isEmpty(), open,
                selected != null && selected.equalsIgnoreCase(path)));
        if (open) {
            for (String kid : kids) {
                walk(kid, depth + 1, spelling, children, counts, selected, rows);
            }
        }
    }

    private static String lower(String path) {
        return path.toLowerCase(Locale.ROOT);
    }
}
