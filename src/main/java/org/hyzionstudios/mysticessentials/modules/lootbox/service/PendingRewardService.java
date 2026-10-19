package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.PendingRewardClaimedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.InventoryAdapter;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PendingInbox;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;
import org.hyzionstudios.mysticessentials.platform.ItemStackMetadata;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The pending inbox: rewards that did not fit and keys granted while offline.
 * A claim takes entries out before giving them and puts back what still does not
 * fit, so a crash at the wrong moment can lose an item but never duplicate one.
 */
public final class PendingRewardService {

    /** {@code claimed} entries handed over, {@code remaining} still waiting. */
    public record ClaimResult(int claimed, int remaining) {
    }

    private final MysticCore core;
    private final LootBoxStorage storage;
    private final AuditService audit;
    private final PlayerLanes lanes;

    public PendingRewardService(MysticCore core, LootBoxStorage storage, AuditService audit, PlayerLanes lanes) {
        this.core = core;
        this.storage = storage;
        this.audit = audit;
        this.lanes = lanes;
    }

    public CompletableFuture<PendingInbox> inbox(UUID player) {
        return storage.loadPending(player).thenApply(inbox -> {
            long now = System.currentTimeMillis();
            inbox.items.removeIf(item -> item.expiresAt > 0 && now >= item.expiresAt);
            return inbox;
        });
    }

    /** Claims everything ({@code fulfillmentId == null}) or one entry. */
    public CompletableFuture<ClaimResult> claim(PlayerRef player, String fulfillmentId) {
        UUID uuid = player.getUuid();
        return lanes.run(uuid, () -> {
            List<PendingInbox.PendingReward> taken = new ArrayList<>();
            long now = System.currentTimeMillis();
            return storage.updatePending(uuid, inbox -> {
                List<PendingInbox.PendingReward> keep = new ArrayList<>();
                for (PendingInbox.PendingReward item : inbox.items) {
                    boolean expired = item.expiresAt > 0 && now >= item.expiresAt;
                    if (expired) {
                        audit.record(uuid.toString(), "PENDING_EXPIRED", item.fulfillmentId, item.itemId);
                    } else if (fulfillmentId == null || fulfillmentId.equals(item.fulfillmentId)) {
                        taken.add(item);
                    } else {
                        keep.add(item);
                    }
                }
                inbox.items = keep;
                return inbox;
            }).thenCompose(afterTake -> give(player, taken)).thenCompose(left -> putBack(uuid, left)
                    .thenApply(inbox -> {
                        int claimed = taken.size() - left.size();
                        if (claimed > 0) {
                            audit.record(uuid.toString(), "PENDING_CLAIM", uuid.toString(), claimed + " entries");
                            core.getEventBus().publish(new PendingRewardClaimedEvent(uuid, claimed, inbox.items.size()));
                        }
                        return new ClaimResult(claimed, inbox.items.size());
                    }));
        });
    }

    private CompletableFuture<List<PendingInbox.PendingReward>> give(PlayerRef player,
            List<PendingInbox.PendingReward> taken) {
        if (taken.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<List<PendingInbox.PendingReward>> left = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            List<PendingInbox.PendingReward> remaining = new ArrayList<>();
            for (PendingInbox.PendingReward item : taken) {
                try {
                    ItemStack remainder = InventoryAdapter.give(store, entity,
                            InventoryAdapter.stack(item.itemId, item.quantity, item.metadata));
                    if (remainder != null) {
                        item.quantity = remainder.getQuantity();
                        item.metadata = ItemStackMetadata.toJson(remainder);
                        remaining.add(item);
                    }
                } catch (Throwable t) {
                    remaining.add(item);
                }
            }
            left.complete(remaining);
        });
        if (!dispatched) {
            left.complete(taken);
        }
        return left;
    }

    private CompletableFuture<PendingInbox> putBack(UUID player, List<PendingInbox.PendingReward> left) {
        return storage.updatePending(player, inbox -> {
            for (PendingInbox.PendingReward item : left) {
                if (!inbox.contains(item.fulfillmentId)) {
                    inbox.items.add(item);
                }
            }
            return inbox;
        });
    }
}
