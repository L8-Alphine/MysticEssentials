package org.hyzionstudios.mysticessentials.modules.lootbox.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One pool as authored in {@code modules/lootbox/lootboxes/<id>.json}. Editing the
 * file changes only the draft; {@code /lootbox publish <id>} validates it and
 * freezes a copy into an immutable {@link LootBoxRevision}.
 */
public final class LootBoxDraft {

    /** {@code [a-z0-9_]{3,48}}. */
    public String id;
    /** Shown in menus; MysticText markup allowed. */
    public String displayName;
    public String description = "";
    /** Item id drawn as the pool's icon in the catalog; blank uses the first item reward. */
    public String icon = "";
    /** {@code WEIGHTED} (integer weights) or {@code EXACT_PERCENT} (percentages summing to exactly 100). */
    public String mode = "WEIGHTED";
    /** Key definitions that open this pool. */
    public List<String> keys = new ArrayList<>();
    /** Keys spent per opening. Phase 1 supports exactly 1. */
    public int keysPerOpening = 1;
    /** Seconds a player waits between openings of this pool; 0 = none. */
    public long cooldownSeconds = 0;
    /** Optional extra permission node needed to open; blank = none. */
    public String permission = "";
    public List<RewardSpec> rewards = new ArrayList<>();

    /** One possible outcome. Exactly one is drawn per opening. */
    public static final class RewardSpec {
        /** Unique within the pool. */
        public String id;
        /** Shown in previews and receipts; MysticText markup allowed. */
        public String name;
        /** Optional label such as {@code Rare}; shown next to the name. */
        public String rarity = "";
        /** {@code ITEM}, {@code COMMAND}, {@code ECONOMY} or {@code PERMISSION}. */
        public String type;
        /** Relative weight in {@code WEIGHTED} mode (positive integer). */
        public long weight;
        /** Exact chance in {@code EXACT_PERCENT} mode as a decimal string, up to 6 places, e.g. {@code "0.0125"}. */
        public String percent;
        /** Broadcast the win to the server after the opening commits. */
        public boolean announce;

        /** ITEM: what to give. */
        public ItemSpec item;
        /** ECONOMY: amount deposited through the server economy. */
        public double amount;
        /** COMMAND: template run from the console; placeholders {playerUuid} {playerName} {quantity} {openingId}. */
        public String command;
        /** COMMAND: id of the allowlisted pattern the rendered command must match. */
        public String allowedPatternId;
        /** COMMAND: the operator asserts re-running it is harmless, so recovery may retry it. */
        public boolean commandIdempotent;
        /** PERMISSION: node granted through LuckPerms. */
        public String node;
        /** PERMISSION: seconds the node lasts; 0 = permanent. */
        public long durationSeconds;
    }

    /** An item reward. {@code metadata} is the stack's BSON metadata as JSON, when it needs any. */
    public static final class ItemSpec {
        public String itemId;
        public int quantity = 1;
        public String metadata;
    }
}
