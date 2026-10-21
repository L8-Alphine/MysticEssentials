package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxTemplate;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;

/**
 * The Template Library's files (P2-H): saved key looks and reward sets in
 * {@code modules/lootbox/templates/}. They go through a {@link DraftStore} of
 * their own, so a template save is optimistic exactly like a draft save, a
 * delete moves the file to {@code templates/_archive/}, and file work never runs
 * on the calling thread. The store's audit actions are renamed from
 * {@code DRAFT_*} to {@code TEMPLATE_*} so the trail tells them apart.
 *
 * <p>The static methods are engine-free; {@code verifyLootBoxScreens} checks them.
 * Applying a template to a draft is {@code ui/editor/TemplateApply}.</p>
 */
public final class TemplateLibrary {

    public static final String FOLDER = "templates";
    public static final int MAX_NAME_LENGTH = 64;

    public static final DraftStore.Kind<LootBoxTemplate> TEMPLATES = new DraftStore.Kind<>("template",
            LootBoxTemplate.class, DraftValidator.LOOTBOX_ID, "must match [a-z0-9_]{3,48}", id -> id,
            template -> template.id, (template, id) -> template.id = id,
            (id, name) -> {
                LootBoxTemplate template = new LootBoxTemplate();
                template.id = id;
                template.name = name;
                template.kind = LootBoxTemplate.REWARD_SET;
                return template;
            },
            id -> id);

    private final DraftStore<LootBoxTemplate> store;

    /**
     * @param folder   {@code modules/lootbox/templates/}
     * @param audit    records the store's changes, renamed to {@code TEMPLATE_*}
     * @param executor where the file work runs
     */
    public TemplateLibrary(Path folder, DraftStore.Auditor audit, Executor executor) {
        DraftLifecycle<LootBoxTemplate> lifecycle = new DraftLifecycle<>(TemplateLibrary::problems,
                template -> OddsEngine.sha256(Json.toString(template)), id -> null, id -> false, template -> false);
        this.store = new DraftStore<>(TEMPLATES, folder, lifecycle, renamed(audit), new DraftStore.Listener<>() {
            @Override
            public void changed(String id, LootBoxTemplate template) {
                // Pages list the folder when they open; nothing is cached.
            }

            @Override
            public void listed(DraftStore.Listing<LootBoxTemplate> listing) {
            }
        }, executor);
    }

    public DraftStore<LootBoxTemplate> store() {
        return store;
    }

    /** {@code DRAFT_SAVE} → {@code TEMPLATE_SAVE}, and so on for every store action. */
    public static DraftStore.Auditor renamed(DraftStore.Auditor audit) {
        return (actor, action, target, detail) -> audit.record(actor,
                action != null && action.startsWith("DRAFT_") ? "TEMPLATE_" + action.substring(6) : action,
                target, detail);
    }

    // ----- Rules ----------------------------------------------------------------

    /** Why a template cannot be saved as it is; empty when it can. */
    public static List<String> problems(LootBoxTemplate template) {
        List<String> problems = new ArrayList<>();
        if (template == null) {
            problems.add("the template is empty");
            return problems;
        }
        if (template.id == null || !DraftValidator.LOOTBOX_ID.matcher(template.id).matches()) {
            problems.add("template id '" + template.id + "' must match [a-z0-9_]{3,48}");
        }
        if (template.name == null || template.name.isBlank()) {
            problems.add("the template has no name");
        } else if (template.name.length() > MAX_NAME_LENGTH) {
            problems.add("the name is longer than " + MAX_NAME_LENGTH + " characters");
        }
        if (LootBoxTemplate.KEY_LOOK.equals(template.kind)) {
            if (template.key == null) {
                problems.add("the key look is missing");
            } else if (template.key.baseItemId == null || template.key.baseItemId.isBlank()) {
                problems.add("the key look has no base item");
            }
        } else if (LootBoxTemplate.REWARD_SET.equals(template.kind)) {
            if (template.rewards == null || template.rewards.isEmpty()) {
                problems.add("the reward set has no rewards");
            } else if (template.rewards.stream().anyMatch(r -> r == null || r.id == null || r.id.isBlank())) {
                problems.add("a reward in the set has no id");
            }
        } else {
            problems.add("unknown template kind '" + template.kind + "'");
        }
        return problems;
    }

    /** A key look copied from key draft {@code key}. Nothing of the key's id or links is kept. */
    public static LootBoxTemplate keyLook(String id, String name, KeyDraft key, String actor, long now) {
        LootBoxTemplate template = base(id, name, LootBoxTemplate.KEY_LOOK, "key " + key.id, actor, now);
        KeyDraft copy = DraftLifecycle.freeze(key, KeyDraft.class);
        LootBoxTemplate.KeyLook look = new LootBoxTemplate.KeyLook();
        look.baseItemId = copy.baseItemId;
        look.name = copy.name;
        look.lore = copy.lore == null ? new ArrayList<>() : new ArrayList<>(copy.lore);
        look.uses = copy.uses;
        look.expiresAfterDays = copy.expiresAfterDays;
        template.key = look;
        return template;
    }

    /** A reward set holding a deep copy of every reward of pool draft {@code pool}, in order. */
    public static LootBoxTemplate rewardSet(String id, String name, LootBoxDraft pool, String actor, long now) {
        LootBoxTemplate template = base(id, name, LootBoxTemplate.REWARD_SET, "pool " + pool.id, actor, now);
        LootBoxDraft copy = DraftLifecycle.freeze(pool, LootBoxDraft.class);
        template.rewards = copy.rewards == null ? new ArrayList<>() : new ArrayList<>(copy.rewards);
        template.rewardMode = copy.mode == null ? "WEIGHTED" : copy.mode.toUpperCase(Locale.ROOT);
        return template;
    }

    /** {@code <source>_look} for a key look, {@code <source>_rewards} for a set, cut to fit the id pattern. */
    public static String suggestId(String sourceId, String kind) {
        String base = (sourceId == null ? "" : sourceId).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        String suffix = LootBoxTemplate.KEY_LOOK.equals(kind) ? "_look" : "_rewards";
        if (base.length() + suffix.length() > 48) {
            base = base.substring(0, 48 - suffix.length());
        }
        String id = base + suffix;
        return id.length() < 3 ? "template" + suffix : id;
    }

    /** A one-line summary for the library's list, e.g. {@code Key look · Ingredient_Bar_Gold · 3 uses}. */
    public static String summary(LootBoxTemplate template) {
        if (LootBoxTemplate.KEY_LOOK.equals(template.kind)) {
            LootBoxTemplate.KeyLook look = template.key;
            return look == null ? "Key look · empty"
                    : "Key look · " + blank(look.baseItemId, "no base item") + " · " + look.uses
                            + (look.uses == 1 ? " use" : " uses")
                            + (look.expiresAfterDays > 0 ? " · expires after " + look.expiresAfterDays + "d" : "");
        }
        int count = template.rewards == null ? 0 : template.rewards.size();
        return "Reward set · " + count + (count == 1 ? " reward" : " rewards") + " · "
                + blank(template.rewardMode, "WEIGHTED");
    }

    private static LootBoxTemplate base(String id, String name, String kind, String source, String actor, long now) {
        LootBoxTemplate template = new LootBoxTemplate();
        template.id = id;
        template.name = name == null ? "" : name.trim();
        template.kind = kind;
        template.source = source;
        template.createdBy = actor == null ? "" : actor;
        template.createdAt = now;
        return template;
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
