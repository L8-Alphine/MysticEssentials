package org.hyzionstudios.mysticessentials.modules.lootbox.model;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable published pool ({@code lootbox_rev/<id>@<revision>}). Openings
 * record the revision they drew from, so later publishes never change a
 * committed result or its receipt.
 */
public final class LootBoxRevision {

    public int schema = 1;
    public String lootboxId;
    public int revision;
    /** Frozen copy of the draft as published. */
    public LootBoxDraft spec;
    /** Resolved integer weight of each reward, in {@code spec.rewards} order. */
    public List<Long> weights = new ArrayList<>();
    /** Resolved weights of each bonus roll (P3-WP2), in {@code spec.bonusRolls} order; empty without any. */
    public List<BonusWeights> bonusWeights = new ArrayList<>();
    /** SHA-256 of the canonical published content. */
    public String contentHash;
    public String publishedBy;
    public long publishedAt;
    /** Set when this revision republished an earlier one. */
    public Integer rolledBackFrom;

    /** One bonus roll's integer weights: each reward's, in order, and "no bonus". */
    public static final class BonusWeights {
        public String rollId;
        public List<Long> weights = new ArrayList<>();
        public long none;

        public BonusWeights() {
        }

        public BonusWeights(String rollId, List<Long> weights, long none) {
            this.rollId = rollId;
            this.weights = new ArrayList<>(weights);
            this.none = none;
        }
    }

    /** The mutable per-pool header ({@code lootbox_def/<id>}). */
    public static final class State {
        public int schema = 1;
        public String lootboxId;
        /** 0 = never published. */
        public int activeRevision;
        public int latestRevision;
        public boolean disabled;
        public String updatedBy;
        public long updatedAt;

        // Schedule (P3-WP5); 0 / false when nothing is scheduled.
        /** A published revision waiting for its start, and when it starts. */
        public int scheduledRevision;
        public long scheduledAt;
        /** When the live revision's time-limited odds end. */
        public long endsAt;
        /** Close the pool at {@link #endsAt} instead of going back to {@link #revertTo}. */
        public boolean closeAtEnd;
        /** The revision that was live before the time-limited one; republished at {@link #endsAt}. */
        public int revertTo;
        /** A staff-scheduled rollback: republish revision {@code rollbackTo} at {@code rollbackAt}. */
        public int rollbackTo;
        public long rollbackAt;
    }
}
