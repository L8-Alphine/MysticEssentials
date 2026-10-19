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
    /** SHA-256 of the canonical published content. */
    public String contentHash;
    public String publishedBy;
    public long publishedAt;
    /** Set when this revision republished an earlier one. */
    public Integer rolledBackFrom;

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
    }
}
