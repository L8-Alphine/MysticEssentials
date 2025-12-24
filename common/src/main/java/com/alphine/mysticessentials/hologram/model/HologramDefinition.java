package com.alphine.mysticessentials.hologram.model;

import java.util.ArrayList;
import java.util.List;

public class HologramDefinition {
    public String name;
    public boolean enabled = true;

    public Anchor anchor = new Anchor();
    public Transform transform = new Transform();
    public Visibility visibility = new Visibility();
    public Style style = new Style();
    public Updates updates = new Updates();

    public List<String> lines = new ArrayList<>();

    public static class Anchor {
        // WORLD = normal hologram anchored in world.
        // BOUND = position controlled externally (NPC nameplate binding).
        public String type = "WORLD";
        public String dimension = "minecraft:overworld";
        public double x, y, z;
    }

    public static class Transform {
        public float scale = 1.0f;

        /** Optional "base" rotation (for FIXED billboard or fancy angles). */
        public float yaw = 0.0f;
        public float pitch = 0.0f;

        /** Display billboard modes: CENTER/FIXED/VERTICAL/HORIZONTAL */
        public String billboard = "CENTER";
    }

    public static class Visibility {
        /** Viewer distance in blocks (manager will use this to decide who sees it). */
        public int viewDistanceBlocks = 48;

        /** If true, allow see-through behavior (client-side-ish). */
        public boolean seeThroughBlocks = false;

        /** If true, despawn display entities when nobody is viewing. */
        public boolean despawnWhenNoViewers = true;
    }

    public static class Style {
        /** Space between lines in blocks. */
        public double lineSpacing = 0.28;

        /** TextDisplay shadow. */
        public boolean shadow = false;

        /** 0-255 (TextDisplay text opacity). */
        public int textOpacity = 255;

        /** "transparent" or "#RRGGBB" or "minecraft:..." later if you want. */
        public String background = "transparent";

        /** If true, use the "default background" flag for TextDisplay. */
        public boolean defaultBackground = false;
    }

    public static class Updates {
        /** Enable placeholder parsing. */
        public boolean placeholdersEnabled = true;

        /**
         * Tick interval for refreshing placeholder-driven lines.
         * (0 = only update on manual edit/save; recommended 10-40)
         */
        public int textUpdateIntervalTicks;

        /** Your %% and {} placeholder routes. */
        public boolean enablePercentPlaceholders = true; // %like_this%
        public boolean enableBracePlaceholders = true;   // {like_this}
    }
}
