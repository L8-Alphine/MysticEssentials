package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsFormatter;

/**
 * What publishing a pool draft would change, as lines of MysticText for the
 * editor's Publish tab and its confirmation: settings that differ, rewards
 * added or removed, and every chance that moves, shown as old % → new %.
 * Engine-free; {@code verifyLootBox} checks it.
 */
public final class DraftDiff {

    /**
     * @param lines        one change per line, MysticText markup
     * @param oddsChange   whether any reward's chance would differ (or rewards come or go)
     * @param changed      whether anything at all differs from the live spec
     */
    public record Diff(List<String> lines, boolean oddsChange, boolean changed) {

        public Diff {
            lines = List.copyOf(lines);
        }
    }

    private DraftDiff() {
    }

    /**
     * @param working   the draft being edited
     * @param published the active revision's spec, or {@code null} when the pool was never published
     */
    public static Diff between(LootBoxDraft working, LootBoxDraft published, OddsFormatter format) {
        Map<String, String> next = chances(working, format);
        if (published == null) {
            List<String> lines = new ArrayList<>();
            lines.add("&7First revision: every reward is new.");
            for (LootBoxDraft.RewardSpec reward : working.rewards) {
                lines.add("&a+ " + name(reward) + " &8· &f" + next.getOrDefault(reward.id, "?"));
            }
            return new Diff(lines, true, true);
        }
        Map<String, String> before = chances(published, format);
        List<String> lines = new ArrayList<>();
        field(lines, "Name", MysticText.stripMarkup(published.displayName), MysticText.stripMarkup(working.displayName));
        field(lines, "Description", published.description, working.description);
        field(lines, "Icon", published.icon, working.icon);
        field(lines, "Odds mode", published.mode, working.mode);
        field(lines, "Cooldown", seconds(published.cooldownSeconds), seconds(working.cooldownSeconds));
        field(lines, "Permission", published.permission, working.permission);
        keys(lines, published.keys, working.keys);

        Map<String, LootBoxDraft.RewardSpec> old = byId(published.rewards);
        Map<String, LootBoxDraft.RewardSpec> now = byId(working.rewards);
        Shares oldShares = Shares.of(published);
        Shares newShares = Shares.of(working);
        boolean oddsChange = false;
        for (LootBoxDraft.RewardSpec reward : working.rewards) {
            LootBoxDraft.RewardSpec previous = old.get(reward.id);
            String chance = next.getOrDefault(reward.id, "?");
            if (previous == null) {
                lines.add("&a+ " + name(reward) + " &8· &f" + chance);
                oddsChange = true;
                continue;
            }
            String was = before.getOrDefault(reward.id, "?");
            boolean chanceMoved = oldShares != null && newShares != null
                    ? !oldShares.sameShare(reward.id, newShares) : !was.equals(chance);
            boolean otherwise = !sameApartFromOdds(previous, reward);
            if (chanceMoved) {
                oddsChange = true;
                lines.add("&e~ " + name(reward) + " &8· &7" + was + " &8→ &f" + chance
                        + (otherwise ? " &8(and its details)" : ""));
            } else if (otherwise) {
                lines.add("&e~ " + name(reward) + " &8· &7details changed, chance stays &f" + chance);
            }
        }
        for (LootBoxDraft.RewardSpec reward : published.rewards) {
            if (!now.containsKey(reward.id)) {
                lines.add("&c- " + name(reward) + " &8· &7was " + before.getOrDefault(reward.id, "?"));
                oddsChange = true;
            }
        }
        boolean changed = !lines.isEmpty();
        if (!changed) {
            lines.add("&7No changes from the live revision.");
        }
        return new Diff(lines, oddsChange, changed);
    }

    /** Exact weights by reward id and their total; {@code null} when the odds cannot be computed yet. */
    private record Shares(Map<String, Long> weights, long total) {

        static Shares of(LootBoxDraft draft) {
            List<Long> resolved;
            try {
                resolved = OddsEngine.resolveWeights(draft);
            } catch (IllegalArgumentException e) {
                return null;
            }
            Map<String, Long> weights = new LinkedHashMap<>();
            long total = 0;
            for (int i = 0; i < draft.rewards.size(); i++) {
                weights.putIfAbsent(draft.rewards.get(i).id, resolved.get(i));
                total += resolved.get(i);
            }
            return new Shares(weights, total);
        }

        /** w / total == w' / total', compared exactly. */
        boolean sameShare(String id, Shares other) {
            Long mine = weights.get(id);
            Long theirs = other.weights.get(id);
            if (mine == null || theirs == null) {
                return false;
            }
            return BigInteger.valueOf(mine).multiply(BigInteger.valueOf(other.total))
                    .equals(BigInteger.valueOf(theirs).multiply(BigInteger.valueOf(total)));
        }
    }

    /** Each reward's displayed chance by id; empty when the odds cannot be computed yet. */
    public static Map<String, String> chances(LootBoxDraft draft, OddsFormatter format) {
        Map<String, String> chances = new LinkedHashMap<>();
        List<Long> weights;
        try {
            weights = OddsEngine.resolveWeights(draft);
        } catch (IllegalArgumentException e) {
            return chances;
        }
        long total = 0;
        for (long weight : weights) {
            total += weight;
        }
        for (int i = 0; i < draft.rewards.size(); i++) {
            chances.put(draft.rewards.get(i).id, format.percent(weights.get(i), total));
        }
        return chances;
    }

    private static void field(List<String> lines, String label, String before, String after) {
        String a = before == null ? "" : before;
        String b = after == null ? "" : after;
        if (!a.equals(b)) {
            lines.add("&e~ " + label + " &8· &7" + (a.isBlank() ? "none" : a) + " &8→ &f" + (b.isBlank() ? "none" : b));
        }
    }

    private static void keys(List<String> lines, List<String> before, List<String> after) {
        Set<String> old = new LinkedHashSet<>(before == null ? List.of() : before);
        Set<String> now = new LinkedHashSet<>(after == null ? List.of() : after);
        for (String key : now) {
            if (!old.contains(key)) {
                lines.add("&a+ key &f" + key);
            }
        }
        for (String key : old) {
            if (!now.contains(key)) {
                lines.add("&c- key &f" + key);
            }
        }
    }

    /** Everything but the weight and percent, compared through the JSON tree the revision stores. */
    private static boolean sameApartFromOdds(LootBoxDraft.RewardSpec a, LootBoxDraft.RewardSpec b) {
        LootBoxDraft.RewardSpec left = Json.gson().fromJson(Json.toTree(a), LootBoxDraft.RewardSpec.class);
        LootBoxDraft.RewardSpec right = Json.gson().fromJson(Json.toTree(b), LootBoxDraft.RewardSpec.class);
        left.weight = 0;
        right.weight = 0;
        left.percent = null;
        right.percent = null;
        return Objects.equals(Json.toString(left), Json.toString(right));
    }

    private static Map<String, LootBoxDraft.RewardSpec> byId(List<LootBoxDraft.RewardSpec> rewards) {
        Map<String, LootBoxDraft.RewardSpec> map = new LinkedHashMap<>();
        for (LootBoxDraft.RewardSpec reward : rewards) {
            map.putIfAbsent(reward.id, reward);
        }
        return map;
    }

    private static String name(LootBoxDraft.RewardSpec reward) {
        String name = reward.name == null || reward.name.isBlank() ? reward.id : MysticText.stripMarkup(reward.name);
        return "&f" + name;
    }

    private static String seconds(long value) {
        return value <= 0 ? "" : value + "s";
    }
}
