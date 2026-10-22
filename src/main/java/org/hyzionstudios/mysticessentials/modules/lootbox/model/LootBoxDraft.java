package org.hyzionstudios.mysticessentials.modules.lootbox.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    // Phase 3 and later. Every one of these defaults to null (meaning "off") and is
    // left out of the content hash while unset, so pools published before they
    // existed keep their hash (DraftLifecycle.canonical).

    /** Independent bonus draws (P3-WP2); {@code null} or empty for none. */
    public List<BonusRoll> bonusRolls;
    /** Who may open at all (P3-WP3): every condition must pass. */
    public List<ConditionSpec> conditions;
    /**
     * What a reward whose own conditions fail does to the player's odds (P3-WP3):
     * {@code RENORMALIZE} (default) leaves it out and recalculates the rest, and
     * the preview says so; {@code BLOCK} refuses the opening instead.
     */
    public String ineligibleRewards;
    /** Pity and guarantees (P3-WP4); {@code null} for none. */
    public Pity pity;
    /**
     * When these odds start (P3-WP5), an ISO-8601 instant such as
     * {@code 2026-12-01T00:00:00Z}. Publishing before then schedules the revision;
     * the preview shows the date. Blank: live as soon as it is published.
     */
    public String startsAt;
    /** When these odds end (P3-WP5), ISO-8601; blank for never. The preview shows a time-limited banner. */
    public String endsAt;
    /**
     * At {@link #endsAt}: {@code REVERT} (default) republishes the revision that
     * was live before these odds, {@code CLOSE} closes the pool.
     */
    public String afterEnd;

    /** The bonus rolls, never {@code null}. */
    public List<BonusRoll> bonusRolls() {
        return bonusRolls == null ? List.of() : bonusRolls;
    }

    /** The pool's own conditions, never {@code null}. */
    public List<ConditionSpec> conditions() {
        return conditions == null ? List.of() : conditions;
    }

    /** Whether an ineligible reward blocks the opening instead of leaving the table. */
    public boolean blocksIneligible() {
        return "BLOCK".equalsIgnoreCase(ineligibleRewards);
    }

    /** One possible outcome. Exactly one is drawn per opening. */
    public static final class RewardSpec {
        /** Unique within the pool. */
        public String id;
        /** Shown in previews and receipts; MysticText markup allowed. */
        public String name;
        /** Optional label such as {@code Rare}; shown next to the name. */
        public String rarity = "";
        /**
         * {@code ITEM}, {@code COMMAND}, {@code ECONOMY} or {@code PERMISSION}; from
         * Phase 3 also {@code BUNDLE} (several guaranteed actions, {@link #bundle})
         * and {@code EXTENSION}, {@code COSMETIC} and {@code RPG}, which a
         * registered {@link #provider} delivers.
         */
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

        // Fields below are Phase 3 and later. They default to null and are left out
        // of the content hash while unset (DraftLifecycle.canonical), so pools
        // published before them keep their hash.

        /**
         * EXTENSION, COSMETIC and RPG (and ECONOMY through MysticEconomy): the id of
         * the {@code RewardProvider} that delivers it. A missing provider makes the
         * pool invalid; nothing is substituted.
         */
        public String provider;
        /** The provider's parameters, passed to it unchanged. */
        public Map<String, String> params;
        /**
         * BUNDLE: the actions this one outcome delivers, every one of them, in
         * order. Each is a reward without odds (its weight and percent are
         * ignored); a bundle can't contain a bundle.
         */
        public List<RewardSpec> bundle;
        /** ECONOMY through a provider: the currency id; blank uses the provider's default. */
        public String currency;
        /** Who may win this reward (P3-WP3): every condition must pass. */
        public List<ConditionSpec> conditions;
        /**
         * Duplicate protection (P3-WP4): {@code ALLOW} (default) or
         * {@code EXCLUDE_OWNED}, which leaves the reward out of a player's odds once
         * they won it from this pool (or, with a provider that can tell, own it).
         * COSMETIC rewards must say which.
         */
        public String duplicates;

        /** Provider parameters, never {@code null}. */
        public Map<String, String> params() {
            return params == null ? Map.of() : params;
        }

        /** This reward's conditions, never {@code null}. */
        public List<ConditionSpec> conditions() {
            return conditions == null ? List.of() : conditions;
        }

        public boolean excludesOwned() {
            return "EXCLUDE_OWNED".equalsIgnoreCase(duplicates);
        }
    }

    /**
     * One eligibility rule (P3-WP3). Built in: {@code PERMISSION} ({@code node}),
     * {@code WORLD} ({@code worlds}, comma-separated), {@code REGION}
     * ({@code world}, {@code min} and {@code max} as {@code x,y,z}) and
     * {@code DATE_WINDOW} ({@code from} and/or {@code until}, ISO-8601 instants).
     * Any other type is answered by a registered {@code ConditionProvider}.
     */
    public static final class ConditionSpec {
        public String type;
        public Map<String, String> params = new java.util.LinkedHashMap<>();
        /** Passes when the rule does NOT hold. */
        public boolean negate;
        /** Shown to players instead of the generated description; blank to generate it. */
        public String label = "";

        public Map<String, String> params() {
            return params == null ? Map.of() : params;
        }
    }

    /**
     * Pity (P3-WP4): counts a player's openings of this pool since they last won
     * one of the {@link #targets}, and raises those rewards' odds or guarantees
     * one. The player's personal odds for the next opening are always shown, with
     * a plain-language summary, before a key is spent.
     */
    public static final class Pity {
        /** Reward ids of the main table that the counter waits for; winning one resets it. */
        public List<String> targets = new ArrayList<>();
        /**
         * Hard pity: the {@code hardAfter}-th opening without a target draws only
         * targets (their own weights, renormalised). 0 = off.
         */
        public int hardAfter;
        /** Soft pity: after this many openings without a target, targets' weights grow. 0 = off. */
        public int softAfter;
        /** Soft pity: percent added to each target's weight per opening past {@link #softAfter}. */
        public int softStepPercent;
    }

    /**
     * An independent extra draw (P3-WP2), e.g. "10% chance of one additional
     * cosmetic". It is rolled on every opening beside the main draw, with its own
     * labelled distribution: its rewards' weights plus a "no bonus" weight. It is
     * never mixed into the main table, so "outcome selected" and "chance of a
     * bonus" are never confused (Design Bible §07).
     */
    public static final class BonusRoll {
        /** {@code [a-z0-9_]{1,48}}, unique within the pool. */
        public String id;
        /** Shown above its rows, e.g. {@code Bonus cosmetic}. MysticText markup allowed. */
        public String label;
        /** {@code WEIGHTED} or {@code EXACT_PERCENT}, like the pool. */
        public String mode = "WEIGHTED";
        /** WEIGHTED: the weight of "no bonus"; 0 makes the bonus certain. */
        public long noneWeight;
        /** EXACT_PERCENT: the chance of "no bonus"; with the rewards it sums to exactly 100. */
        public String nonePercent;
        public List<RewardSpec> rewards = new ArrayList<>();
    }

    /** An item reward. {@code metadata} is the stack's BSON metadata as JSON, when it needs any. */
    public static final class ItemSpec {
        public String itemId;
        public int quantity = 1;
        public String metadata;
    }
}
