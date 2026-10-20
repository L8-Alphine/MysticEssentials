package org.hyzionstudios.mysticessentials.modules.lootbox.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A saved template in {@code modules/lootbox/templates/<id>.json} (P2-H): a key
 * look to copy onto key drafts, or a reward set to insert into pool drafts.
 * Templates are draft-like files: the Template Library writes them with the
 * same optimistic saves as drafts, and they never reach storage.
 */
public final class LootBoxTemplate {

    public static final String KEY_LOOK = "KEY_LOOK";
    public static final String REWARD_SET = "REWARD_SET";

    /** {@code [a-z0-9_]{3,48}}. */
    public String id;
    /** {@link #KEY_LOOK} or {@link #REWARD_SET}. */
    public String kind;
    /** Shown in the library; plain text. */
    public String name;
    /** Where it was saved from, e.g. {@code key mystic:royal_key}. */
    public String source = "";
    public String createdBy = "";
    public long createdAt;

    /** KEY_LOOK: what a key draft takes from it. */
    public KeyLook key;

    /** REWARD_SET: the rewards, copied whole. */
    public List<LootBoxDraft.RewardSpec> rewards = new ArrayList<>();
    /** REWARD_SET: the mode of the pool it was saved from, {@code WEIGHTED} or {@code EXACT_PERCENT}. */
    public String rewardMode = "WEIGHTED";

    /** The look of a key: everything about it but its id. */
    public static final class KeyLook {
        public String baseItemId;
        /** MysticText markup. */
        public String name;
        public List<String> lore = new ArrayList<>();
        public int uses = 1;
        public long expiresAfterDays = 0;
    }
}
