package com.alphine.mysticessentials.commands;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks which root command belongs to which mod id. Call from each platform during command registration. */
public final class CommandIndex {
    private static final Map<String, String> ROOT_TO_MOD = new ConcurrentHashMap<>();

    public static void indexRoot(String rootLiteral, String modId) {
        ROOT_TO_MOD.put(rootLiteral.toLowerCase(), modId.toLowerCase());
    }

    public static String modOf(String rootLiteral) {
        return ROOT_TO_MOD.getOrDefault(rootLiteral.toLowerCase(), "unknown");
    }

    private CommandIndex() {}
}
