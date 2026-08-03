package org.hyzionstudios.mysticessentials.core.item;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted settings for {@code modules/chat/item-view.json} — how items are
 * inspected and how the ItemView panel presents them.
 */
public final class ItemViewConfig {

    public boolean enabled = true;

    public Display display = new Display();
    public Sections sections = new Sections();
    public Fallback fallback = new Fallback();
    public Snapshots snapshots = new Snapshots();
    public Providers providers = new Providers();

    /**
     * Quality definitions keyed by the engine's {@code Item.getQualityIndex()}.
     * An index with no entry here yields <b>no</b> quality on the item — genuine
     * absence — rather than a made-up placeholder.
     */
    public List<QualityDefinition> qualities = defaultQualities();

    /**
     * Item-id rules that assign a classification the engine does not expose
     * (vanilla {@code getQualityIndex()} is 0 for most custom RPG items). First
     * match wins; a rule may set any subset of quality/rarity/tier/grade.
     */
    public List<ClassificationRule> classificationRules = defaultClassificationRules();

    public static final class Display {
        public boolean showQuality = true;
        public boolean showRarity = true;
        public boolean showTier = true;
        public boolean showGrade = true;
        public boolean showItemId = false;
        public boolean showSourceMod = true;
        public boolean showOriginalTooltipButton = true;
        /** Switch to the single-column compact layout below this window width. */
        public int compactBelowWidth = 900;
    }

    public static final class Sections {
        public boolean classification = true;
        public boolean primaryStatistics = true;
        public boolean secondaryStatistics = true;
        public boolean modifiers = true;
        public boolean customSections = true;
        public boolean requirements = true;
        public boolean description = true;
        public boolean lore = true;
        public boolean durability = true;
        public boolean shareInformation = true;
        public boolean technicalInformation = true;
    }

    public static final class Fallback {
        /**
         * When an item has no quality at all, hide the badge entirely (the
         * recommended behaviour) rather than inventing a label.
         */
        public boolean hideMissingQuality = true;
        /**
         * Shown only when {@link #hideMissingQuality} is {@code false} <i>and</i>
         * the item genuinely has no quality. This is never substituted for a
         * quality whose name happens to be "Null", "None", or "Unknown" — those
         * are real names and are displayed as-is.
         */
        public String missingQualityDisplayName = "Unclassified";
        /** Neutral accent used when no classification supplies a colour. */
        public String neutralAccentColor = "#7a9cc6";
    }

    public static final class Snapshots {
        /** Minutes a captured snapshot stays inspectable. */
        public int expirationMinutes = 30;
        /** Snapshots retained per sharing player. */
        public int maxPerPlayer = 50;
        /** Hard cap on live snapshots across the server. */
        public int maximumSnapshots = 500;
        /** Ceiling on the serialized size of one snapshot's metadata. */
        public int maximumMetadataBytes = 65536;
        /** Recent shared-item entries kept per recipient. */
        public int historyEntriesPerPlayer = 25;
    }

    public static final class Providers {
        /** Contain a throwing provider instead of failing the whole inspection. */
        public boolean catchProviderErrors = true;
        public boolean logProviderErrors = true;
        /** Suppress repeat logs from the same failing provider within this window. */
        public int errorLogCooldownSeconds = 60;
    }

    /** A quality tier: engine index &rarr; the name and styling shown for it. */
    public static final class QualityDefinition {
        public int index;
        public String id;
        public String name;
        public String color;
        public String accentColor;

        public QualityDefinition() {
        }

        public QualityDefinition(int index, String id, String name, String color) {
            this.index = index;
            this.id = id;
            this.name = name;
            this.color = color;
        }
    }

    /**
     * An item-id rule. {@code match} is a case-insensitive substring, or a regex
     * over the whole id when {@code regex} is set. Any field left null is simply
     * not contributed, so a rule can set only a tier without inventing a rarity.
     */
    public static final class ClassificationRule {
        public String match;
        public boolean regex = false;
        public String quality;
        public String rarity;
        public String tier;
        public String grade;
        public String color;

        public ClassificationRule() {
        }

        public ClassificationRule(String match, String rarity, String color) {
            this.match = match;
            this.rarity = rarity;
            this.color = color;
        }
    }

    private static List<QualityDefinition> defaultQualities() {
        List<QualityDefinition> list = new ArrayList<>();
        list.add(new QualityDefinition(0, "mysticessentials:common", "Common", "#FFFFFF"));
        list.add(new QualityDefinition(1, "mysticessentials:uncommon", "Uncommon", "#55FF55"));
        list.add(new QualityDefinition(2, "mysticessentials:rare", "Rare", "#5599FF"));
        list.add(new QualityDefinition(3, "mysticessentials:epic", "Epic", "#C24BFF"));
        list.add(new QualityDefinition(4, "mysticessentials:legendary", "Legendary", "#FF9D24"));
        list.add(new QualityDefinition(5, "mysticessentials:mythic", "Mythic", "#FF4D4D"));
        return list;
    }

    private static List<ClassificationRule> defaultClassificationRules() {
        List<ClassificationRule> list = new ArrayList<>();
        list.add(new ClassificationRule("mythic", "Mythic", "#FF4D4D"));
        list.add(new ClassificationRule("legendary", "Legendary", "#FF9D24"));
        list.add(new ClassificationRule("endgame", "Epic", "#C24BFF"));
        list.add(new ClassificationRule("epic", "Epic", "#C24BFF"));
        list.add(new ClassificationRule("rare", "Rare", "#5599FF"));
        list.add(new ClassificationRule("uncommon", "Uncommon", "#55FF55"));
        return list;
    }

    /** Clamps out-of-range values and restores nulled-out blocks after an edit. */
    public ItemViewConfig normalized() {
        ItemViewConfig defaults = new ItemViewConfig();
        if (display == null) {
            display = defaults.display;
        }
        if (sections == null) {
            sections = defaults.sections;
        }
        if (fallback == null) {
            fallback = defaults.fallback;
        }
        if (snapshots == null) {
            snapshots = defaults.snapshots;
        }
        if (providers == null) {
            providers = defaults.providers;
        }
        if (qualities == null) {
            qualities = defaults.qualities;
        }
        if (classificationRules == null) {
            classificationRules = defaults.classificationRules;
        }
        if (fallback.missingQualityDisplayName == null || fallback.missingQualityDisplayName.isBlank()) {
            fallback.missingQualityDisplayName = defaults.fallback.missingQualityDisplayName;
        }
        if (fallback.neutralAccentColor == null || fallback.neutralAccentColor.isBlank()) {
            fallback.neutralAccentColor = defaults.fallback.neutralAccentColor;
        }
        snapshots.expirationMinutes = clamp(snapshots.expirationMinutes, 1, 24 * 60, 30);
        snapshots.maxPerPlayer = clamp(snapshots.maxPerPlayer, 1, 500, 50);
        snapshots.maximumSnapshots = clamp(snapshots.maximumSnapshots, 1, 20_000, 500);
        snapshots.maximumMetadataBytes = clamp(snapshots.maximumMetadataBytes, 1024, 1_048_576, 65536);
        snapshots.historyEntriesPerPlayer = clamp(snapshots.historyEntriesPerPlayer, 1, 200, 25);
        providers.errorLogCooldownSeconds = clamp(providers.errorLogCooldownSeconds, 0, 3600, 60);
        display.compactBelowWidth = clamp(display.compactBelowWidth, 480, 3840, 900);
        return this;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            return fallback;
        }
        return value;
    }
}
