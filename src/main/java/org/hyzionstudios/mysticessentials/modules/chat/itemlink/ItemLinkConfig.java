package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted settings for {@code modules/chat/item-links.json} (loaded through the
 * {@code moduleExtraConfigFile} convention, mirroring rank-icons).
 *
 * <p>Reflects the <b>verified 0.5.6 reality</b>: inline {@code FormattedMessage.image}
 * does not render in chat, so there is no "render item icon in chat" toggle — the
 * chat line carries only the formatted, colour-coded display name (optionally a
 * click-through link), and the icon is shown exclusively in the Item Details and
 * Recent Item Links custom UI panels, which render real {@code ItemSlot} elements.</p>
 */
public final class ItemLinkConfig {

    public boolean enabled = true;
    /** The literal tag a player types in chat to share their held item. */
    public String tag = "[item]";
    /** Permission required to use the tag (blank/null = everyone). */
    public String usePermission = "mysticessentials.chat.itemlink.use";
    /** Max item tags expanded per chat message (all resolve to the one held item). */
    public int maxTagsPerMessage = 3;
    /**
     * Wrap the chat display name in a {@code <link:/itemview CODE>} so a click
     * opens the details page directly. Harmless if the client treats chat links as
     * URLs only — the visible view command below is the guaranteed path.
     */
    public boolean linkChatNameToInspect = true;
    /**
     * Append a visible, typeable {@code (/itemview CODE)} hint after the chat name.
     * This is the reliable, click-independent way to open the viewer: the recipient
     * types (or clicks) exactly what is shown, on keyboard or controller.
     */
    public boolean showViewCommandInChat = true;
    /** The command shown/used to open the viewer (also registered as an alias). */
    public String viewCommand = "itemview";
    /** Underline the chat name to hint interactivity. */
    public boolean underlineChatName = true;
    /** Show the {@code ×quantity} suffix on the chat name. */
    public boolean showQuantityInChat = true;

    public Snapshot snapshot = new Snapshot();
    public History history = new History();
    public InspectionUi inspectionUi = new InspectionUi();
    /**
     * Rarity resolved by matching the item id against these rules (first match
     * wins), because the vanilla {@code getQualityIndex()} is 0 for custom RPG
     * items. Edit these to match your naming convention. Falls back to
     * {@link #rarities} (quality-index map) then Common when nothing matches.
     */
    public List<RarityRule> rarityRules = defaultRarityRules();
    public List<Rarity> rarities = defaultRarities();

    public static final class Snapshot {
        /** Seconds a captured snapshot stays inspectable before it is pruned. */
        public int retentionSeconds = 600;
        /** Hard cap on live snapshots kept in memory. */
        public int maximumSnapshots = 500;
    }

    public static final class History {
        public boolean enabled = true;
        /** Recent shared-item entries kept per recipient. */
        public int maximumEntries = 25;
    }

    public static final class InspectionUi {
        public boolean enabled = true;
        public boolean showDescription = true;
        public boolean showStats = true;
        public boolean showDurability = true;
        public boolean showRequirements = true;
        public boolean showShareInformation = true;
    }

    /** Quality-tier &rarr; display name + colour, indexed by {@code Item.getQualityIndex()}. */
    public static final class Rarity {
        public int index;
        public String name;
        public String color;

        public Rarity() {
        }

        public Rarity(int index, String name, String color) {
            this.index = index;
            this.name = name;
            this.color = color;
        }
    }

    /** An item-id &rarr; rarity rule. {@code match} is a case-insensitive substring, or a regex if {@code regex}. */
    public static final class RarityRule {
        public String match;
        public boolean regex = false;
        public String rarity;
        public String color;

        public RarityRule() {
        }

        public RarityRule(String match, String rarity, String color) {
            this.match = match;
            this.rarity = rarity;
            this.color = color;
        }
    }

    private static List<RarityRule> defaultRarityRules() {
        // First match wins. Seeded with common rarity keywords AND the "endgame"
        // convention seen in-server (Endgame_Frozen_Sword = Epic). Adjust to taste.
        List<RarityRule> list = new ArrayList<>();
        list.add(new RarityRule("mythic", "Mythic", "#FF4D4D"));
        list.add(new RarityRule("legendary", "Legendary", "#FF9D24"));
        list.add(new RarityRule("endgame", "Epic", "#C24BFF"));
        list.add(new RarityRule("epic", "Epic", "#C24BFF"));
        list.add(new RarityRule("rare", "Rare", "#5599FF"));
        list.add(new RarityRule("uncommon", "Uncommon", "#55FF55"));
        return list;
    }

    private static List<Rarity> defaultRarities() {
        List<Rarity> list = new ArrayList<>();
        list.add(new Rarity(0, "Common", "#FFFFFF"));
        list.add(new Rarity(1, "Uncommon", "#55FF55"));
        list.add(new Rarity(2, "Rare", "#5599FF"));
        list.add(new Rarity(3, "Epic", "#C24BFF"));
        list.add(new Rarity(4, "Legendary", "#FF9D24"));
        list.add(new Rarity(5, "Mythic", "#FF4D4D"));
        return list;
    }
}
