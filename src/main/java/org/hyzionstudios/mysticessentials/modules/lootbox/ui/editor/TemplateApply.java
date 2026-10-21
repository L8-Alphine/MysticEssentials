package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxTemplate;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;

/**
 * What applying a template does to a draft (P2-H), computed on copies so the
 * Template Library can show it before anything is written. A key look replaces
 * a key's base item, name, lore, uses and expiry; a reward set appends its
 * rewards to a pool, renaming any id the pool already uses with
 * {@link RewardsTab#nextId}, the rule Add and Duplicate follow; a crate look
 * replaces a placed crate's particles, sound and floating name. Engine-free;
 * {@code verifyLootBoxScreens} checks it.
 */
public final class TemplateApply {

    private TemplateApply() {
    }

    /** A copy of {@code key} with the template's look. The id and anything else the key has are kept. */
    public static KeyDraft keyLook(LootBoxTemplate template, KeyDraft key) {
        KeyDraft result = DraftLifecycle.freeze(key, KeyDraft.class);
        LootBoxTemplate.KeyLook look = template.key;
        result.baseItemId = look.baseItemId;
        result.name = look.name;
        result.lore = look.lore == null ? new ArrayList<>() : new ArrayList<>(look.lore);
        result.uses = look.uses;
        result.expiresAfterDays = look.expiresAfterDays;
        return result;
    }

    /** The change a key look makes, as MysticText lines for the confirmation. */
    public static List<String> keyLookLines(KeyDraft before, KeyDraft after) {
        if (!KeyDiff.changed(after, before)) {
            return List.of("&7Nothing changes: the key already looks like this.");
        }
        return KeyDiff.lines(after, before);
    }

    /**
     * Inserting a reward set.
     *
     * @param pool    a copy of the pool with the rewards appended
     * @param added   the inserted rewards' ids, in order
     * @param renamed set id → id in the pool, for every reward whose id was taken, in set order
     * @param notes   what the designer should check afterwards (MysticText)
     */
    public record Insertion(LootBoxDraft pool, List<String> added, Map<String, String> renamed, List<String> notes,
            boolean hasCommand) {

        /** The confirmation's lines: what is added, what is renamed, then the notes. */
        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add("&7Appends &f" + added.size() + " &7reward(s) to &f" + pool.id + "&7, after its &f"
                    + (pool.rewards.size() - added.size()) + " &7existing one(s). Nothing is removed.");
            if (!renamed.isEmpty()) {
                List<String> pairs = new ArrayList<>();
                renamed.forEach((from, to) -> pairs.add(from + " → " + to));
                lines.add("&eIds already in the pool get new ones: &f" + String.join(", ", pairs));
            }
            lines.addAll(notes);
            return lines;
        }
    }

    /** Appends deep copies of the set's rewards to a copy of {@code pool}. */
    public static Insertion rewardSet(LootBoxTemplate template, LootBoxDraft pool) {
        LootBoxDraft result = DraftLifecycle.freeze(pool, LootBoxDraft.class);
        if (result.rewards == null) {
            result.rewards = new ArrayList<>();
        }
        List<String> added = new ArrayList<>();
        Map<String, String> renamed = new LinkedHashMap<>();
        boolean hasCommand = false;
        for (LootBoxDraft.RewardSpec source : template.rewards) {
            LootBoxDraft.RewardSpec copy = DraftLifecycle.freeze(source, LootBoxDraft.RewardSpec.class);
            String id = RewardsTab.nextId(result, source.id);
            if (!id.equals(source.id)) {
                renamed.put(source.id, id);
            }
            copy.id = id;
            result.rewards.add(copy);
            added.add(id);
            hasCommand |= "COMMAND".equalsIgnoreCase(copy.type);
        }
        List<String> notes = new ArrayList<>();
        String poolMode = mode(result.mode);
        String setMode = mode(template.rewardMode);
        if (!poolMode.equals(setMode)) {
            notes.add("&eThe set was saved from a " + setMode + " pool and " + result.id + " is " + poolMode
                    + ": give the new rewards their " + (OddsConverter.EXACT_PERCENT.equals(poolMode) ? "percents"
                            : "weights") + " on the Rewards tab before publishing.");
        }
        if (OddsConverter.EXACT_PERCENT.equals(poolMode)) {
            notes.add("&7After inserting: " + RewardsTab.sumLine(result));
        }
        if (hasCommand) {
            notes.add("&eIt has COMMAND rewards, which need the rewards.command permission.");
        }
        return new Insertion(result, List.copyOf(added), Collections.unmodifiableMap(renamed), List.copyOf(notes),
                hasCommand);
    }

    /** A copy of {@code crate} with the template's visuals. Its pool, place, behaviour and protection are kept. */
    public static CrateInstance crateLook(LootBoxTemplate template, CrateInstance crate) {
        CrateInstance result = crate.copy();
        LootBoxTemplate.CrateLook look = template.crate;
        CrateInstance.Visuals visuals = new CrateInstance.Visuals();
        visuals.idleParticle = look.idleParticle == null ? "" : look.idleParticle;
        visuals.openParticle = look.openParticle == null ? "" : look.openParticle;
        visuals.openSound = look.openSound == null ? "" : look.openSound;
        visuals.nameplate = look.nameplate;
        result.visuals = visuals;
        return result;
    }

    /** The change a crate look makes, as MysticText lines for the confirmation. */
    public static List<String> crateLookLines(CrateInstance before, CrateInstance after) {
        CrateInstance.Visuals was = before.visuals == null ? new CrateInstance.Visuals() : before.visuals;
        CrateInstance.Visuals now = after.visuals;
        List<String> lines = new ArrayList<>();
        change(lines, "Idle particles", was.idleParticle, now.idleParticle);
        change(lines, "Opening particles", was.openParticle, now.openParticle);
        change(lines, "Opening sound", was.openSound, now.openSound);
        if (was.nameplate != now.nameplate) {
            lines.add("&7Floating name: &f" + (was.nameplate ? "on" : "off") + " &7→ &f" + (now.nameplate ? "on" : "off"));
        }
        return lines.isEmpty() ? List.of("&7Nothing changes: the crate already looks like this.") : lines;
    }

    private static void change(List<String> lines, String what, String was, String now) {
        String before = was == null || was.isBlank() ? "none" : was;
        String after = now == null || now.isBlank() ? "none" : now;
        if (!before.equals(after)) {
            lines.add("&7" + what + ": &f" + before + " &7→ &f" + after);
        }
    }

    /** The set's reward names for the library's detail panel, at most {@code limit}, then "+n more". */
    public static String rewardNames(LootBoxTemplate template, int limit) {
        List<String> names = new ArrayList<>();
        List<LootBoxDraft.RewardSpec> rewards = template.rewards == null ? List.of() : template.rewards;
        for (int i = 0; i < rewards.size() && i < limit; i++) {
            LootBoxDraft.RewardSpec reward = rewards.get(i);
            String name = reward.name == null || reward.name.isBlank() ? reward.id : MysticText.stripMarkup(reward.name);
            names.add(name + " (" + (reward.type == null ? "?" : reward.type.toUpperCase(Locale.ROOT)) + ")");
        }
        String joined = String.join(", ", names);
        return rewards.size() > limit ? joined + ", +" + (rewards.size() - limit) + " more" : joined;
    }

    private static String mode(String mode) {
        return OddsConverter.EXACT_PERCENT.equalsIgnoreCase(mode) ? OddsConverter.EXACT_PERCENT : "WEIGHTED";
    }
}
