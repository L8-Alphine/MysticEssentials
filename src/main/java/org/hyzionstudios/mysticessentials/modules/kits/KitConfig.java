package org.hyzionstudios.mysticessentials.modules.kits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted settings for {@code modules/kits/config.json}. Each kit is a named
 * list of items with optional gating: a per-kit cooldown, a required total
 * online time before (re)claiming, an explicit permission node
 * ({@code mysticessentials.kit.<name>}), and a one-time flag. The kit named by
 * {@code firstJoinKit} is granted automatically on a player's first-ever join.
 */
public final class KitConfig {

    public int configVersion = 1;

    /** Kit granted automatically on first join; blank disables. */
    public String firstJoinKit = "starter";

    public Map<String, Kit> kits = defaultKits();

    public static final class Kit {
        /** Optional pretty name shown in menus; blank falls back to the title-cased kit id. */
        public String displayName;
        /** Items given, in order. Items that no longer resolve are skipped with a log line. */
        public List<KitItem> items = new ArrayList<>();
        /** Seconds between claims; 0 = no cooldown, -1 = single-use (once ever). */
        public long cooldownSeconds = 0;
        /** Total playtime (seconds) a player must have before claiming; 0 = none. */
        public long requiredOnlineSeconds = 0;
        /** When true the kit needs {@code mysticessentials.kit.<name>}. */
        public boolean requirePermission = false;
        /** Optional economy cost per claim (needs VaultUnlocked). */
        public double cost = 0.0;
        /** Optional description shown in /kit list. */
        public String description;
    }

    public static final class KitItem {
        public String itemId;
        public int quantity = 1;

        public KitItem() {
        }

        public KitItem(String itemId, int quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }
    }

    private static Map<String, Kit> defaultKits() {
        Map<String, Kit> kits = new LinkedHashMap<>();

        Kit starter = new Kit();
        starter.displayName = "Starter Kit";
        starter.description = "Basic tools for new players";
        starter.cooldownSeconds = -1;
        starter.items.add(new KitItem("Tool_Pickaxe_Copper", 1));
        starter.items.add(new KitItem("Tool_Hatchet_Copper", 1));
        starter.items.add(new KitItem("Plant_Fruit_Apple", 8));
        kits.put("starter", starter);

        Kit daily = new Kit();
        daily.displayName = "Daily Bundle";
        daily.description = "A small daily bundle";
        daily.cooldownSeconds = 86400;
        daily.requiredOnlineSeconds = 3600;
        daily.items.add(new KitItem("Plant_Fruit_Apple", 4));
        kits.put("daily", daily);

        return kits;
    }
}
