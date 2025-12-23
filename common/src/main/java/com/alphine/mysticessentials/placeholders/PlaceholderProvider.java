package com.alphine.mysticessentials.placeholders;

import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface PlaceholderProvider {
    /**
     * @param key placeholder key WITHOUT braces/percent (ex: "player", "world")
     * @return resolved string, or null if not handled
     */
    @Nullable String resolve(String key, PlaceholderContext ctx);
}
