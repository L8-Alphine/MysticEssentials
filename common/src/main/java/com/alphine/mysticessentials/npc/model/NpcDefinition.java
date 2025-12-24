package com.alphine.mysticessentials.npc.model;

import net.minecraft.world.entity.EquipmentSlot;

import java.util.*;

public class NpcDefinition {
    public String name;
    public boolean enabled = true;

    public Anchor anchor = new Anchor();
    public Type type = new Type();
    public Display display = new Display();
    public Behavior behavior = new Behavior();
    public Nameplate nameplate = new Nameplate();
    public Visuals visuals = new Visuals();
    public Dialogue dialogue = new Dialogue();

    // RIGHT/LEFT/MIDDLE/ANY -> list of commands
    public Map<String, java.util.List<String>> interactions = new java.util.HashMap<>();

    /** Equipment stored as SNBT of ItemStack#save(...) */
    public Equipment equipment = new Equipment();

    public static class Equipment {
        /** slotName -> SNBT (ItemStack) */
        public Map<String, String> items = new java.util.HashMap<>();

        /** slotName -> visible? (default true when missing) */
        public Map<String, Boolean> visible = new java.util.HashMap<>();

        public boolean isVisible(String slotName) {
            if (visible == null) return true;
            Boolean v = visible.get(normSlot(slotName));
            return v == null || v; // default: visible
        }

        public void setVisible(String slotName, boolean value) {
            if (visible == null) visible = new java.util.HashMap<>();
            visible.put(normSlot(slotName), value);
        }
    }

    public static String normSlot(String raw) {
        return raw == null ? "" : raw.toUpperCase(Locale.ROOT);
    }

    public static EquipmentSlot toEquipmentSlot(String raw) {
        String s = normSlot(raw);
        return switch (s) {
            case "MAINHAND", "HAND", "MAIN" -> EquipmentSlot.MAINHAND;
            case "OFFHAND", "OFF" -> EquipmentSlot.OFFHAND;
            case "HEAD", "HELMET" -> EquipmentSlot.HEAD;
            case "CHEST", "CHESTPLATE" -> EquipmentSlot.CHEST;
            case "LEGS", "LEGGINGS" -> EquipmentSlot.LEGS;
            case "FEET", "BOOTS" -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    public static class Anchor {
        public String dimension = "minecraft:overworld";
        public double x, y, z;
        public float yaw = 0f, pitch = 0f;
    }

    public static class Type {
        // PLAYER or ENTITY
        public String kind = "PLAYER";

        public PlayerProfile playerProfile = new PlayerProfile();
        public String entityType = "minecraft:villager";

        public static class PlayerProfile {
            public String name = "Npc";
            public Skin skin = new Skin();

            public static class Skin {
                // MIRROR_VIEWER / ONLINE_PLAYER / MOJANG_USER / URL
                public String source = "MOJANG_USER";
                public String value = "Steve";

                /** Cache TTL for resolved skins (seconds). */
                public int cacheTtlSeconds = 86400;
            }
        }
    }

    public static class Display {
        /** Used for ENTITY custom name or for your own UI labeling. */
        public String displayName = "&eNPC";
        public float scale = 1.0f;
    }

    public static class Visuals {
        public boolean glowing = false;
        /** WHITE/RED/BLUE/etc (we’ll map later) */
        public String glowingColor = "WHITE";
    }

    public static class Behavior {
        public LookAtPlayer lookAtPlayer = new LookAtPlayer();
        public EntityOptions entityOptions = new EntityOptions();
        public Path path = new Path();

        public static class LookAtPlayer {
            public boolean enabled = false;
            public double rangeBlocks = 8.0;
            public int updateIntervalTicks = 4;
            public boolean returnToOriginalWhenOutOfRange = true;
            public float maxYawPerTick = 20f;
            public float maxPitchPerTick = 15f;
        }

        public static class EntityOptions {
            public boolean noAI = true;
            public boolean silent = true;

            /** If true, NPC is setInvulnerable(true). */
            public boolean invulnerable = true;

            /**
             * If false, plugin will try to cancel equipment interactions (right-click
             * on armor stands, etc.). Event hooks are handled separately per platform.
             */
            public boolean allowEquipmentInteraction = false;

            /**
             * If false, plugin will try to cancel attacks against this NPC
             * (on top of setInvulnerable).
             */
            public boolean allowDamage = false;
        }

        public static class Path {
            public boolean enabled = false;
            /** Blocks per second, 0 = teleport directly between waypoints. */
            public double speedBlocksPerSecond = 0.25;
            public List<Waypoint> waypoints = new ArrayList<>();

            public static class Waypoint {
                public String dimension = "minecraft:overworld";
                public double x, y, z;
                public float yaw = 0f, pitch = 0f;
                /** How long to wait at this point before moving on. */
                public int waitTicks = 0;
            }
        }
    }

    public static class Nameplate {
        /** If enabled, npc will hide vanilla nameplate and attach hologram. */
        public boolean enabled = false;

        /** NPC -> Hologram name */
        public String hologram = "";

        public Offset offset = new Offset();

        /** Hide the entity custom name (vanilla nameplate) when hologram is used. */
        public boolean hideEntityName = true;

        public static class Offset {
            public double x = 0.0, y = 2.35, z = 0.0;
        }
    }

    public static class Dialogue {
        /** Master toggle for auto dialogue on click. */
        public boolean enabled = false;

        /** SEQUENTIAL or RANDOM */
        public String mode = "SEQUENTIAL";

        /** Plain strings for now; you can feed them through your chat formatter later. */
        public List<String> lines = new ArrayList<>();
    }
}
