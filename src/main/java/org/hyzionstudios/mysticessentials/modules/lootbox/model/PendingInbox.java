package org.hyzionstudios.mysticessentials.modules.lootbox.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewards waiting to be claimed ({@code lootbox_pending/<uuid>}): items that did
 * not fit, and physical keys granted while the player was offline. Entries are
 * added at most once per {@code fulfillmentId}, so replaying a delivery is safe.
 */
public final class PendingInbox {

    public int schema = 1;
    /** The highest network fencing token that wrote this inbox (P4-WP2); 0 for local writes. */
    public long fence;
    public List<PendingReward> items = new ArrayList<>();

    public boolean contains(String fulfillmentId) {
        for (PendingReward item : items) {
            if (fulfillmentId.equals(item.fulfillmentId)) {
                return true;
            }
        }
        return false;
    }

    public static final class PendingReward {
        public String fulfillmentId;
        public String openingId;
        public String label;
        public String itemId;
        public int quantity = 1;
        /** Stack metadata as JSON, when it has any (key markers live here). */
        public String metadata;
        public long createdAt;
        /** 0 = never expires. */
        public long expiresAt;
    }
}
