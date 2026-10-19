package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.RewardDeliveryCompletedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.RewardDeliveryFailedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.InventoryAdapter;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PendingInbox;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.CommandTemplate;
import org.hyzionstudios.mysticessentials.platform.ItemStackMetadata;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Delivers a committed outcome (§4.4). Every action is recorded ATTEMPTED before
 * it runs and DELIVERED after, so a crash in between is visible. Only actions
 * that are safe to repeat (inbox entries, permission nodes, templates marked
 * idempotent) are retried; everything else goes to REVIEW for staff. The
 * deliveries this server is running are tracked, so recovery leaves them to finish.
 */
public final class RewardService {

    /** Final status of one opening's delivery, shown on the reveal and in history. */
    public enum Status {
        DELIVERED("Delivered"),
        PENDING_INBOX("In your pending inbox"),
        REVIEW("Under review");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final MysticCore core;
    private final LootBoxStorage storage;
    private final AuditService audit;
    private volatile LootBoxConfig config;
    /** Openings this server is delivering, with when each delivery started. */
    private final Map<String, Long> delivering = new ConcurrentHashMap<>();

    public RewardService(MysticCore core, LootBoxStorage storage, AuditService audit, LootBoxConfig config) {
        this.core = core;
        this.storage = storage;
        this.audit = audit;
        this.config = config;
    }

    public void updateConfig(LootBoxConfig config) {
        this.config = config;
    }

    /**
     * Runs every unsettled action of {@code opening}. {@code force} also re-runs
     * actions in REVIEW ({@code /lootbox reconcile … regrant}).
     */
    public CompletableFuture<Status> deliver(Opening opening, LootBoxDraft.RewardSpec reward, boolean force) {
        UUID player = UUID.fromString(opening.player);
        // Registered before the first step: the opening's lane lets go as soon as
        // this returns, and recovery in that lane must already see it running.
        long started = System.currentTimeMillis();
        delivering.put(opening.openingId, started);
        CompletableFuture<Status> chain = CompletableFuture.completedFuture(Status.DELIVERED);
        for (int i = 0; i < opening.fulfillments.size(); i++) {
            int index = i;
            chain = chain.thenCompose(worst -> deliverOne(opening, index, reward, player, force)
                    .thenApply(status -> worse(worst, status)));
        }
        return chain.thenCompose(status -> settle(opening, player, status))
                .whenComplete((status, error) -> delivering.remove(opening.openingId, started));
    }

    /** When this server started delivering {@code openingId}, or {@code null} if it is not delivering it. */
    public Long deliveringSince(String openingId) {
        return delivering.get(openingId);
    }

    private CompletableFuture<Status> deliverOne(Opening opening, int index, LootBoxDraft.RewardSpec reward,
            UUID player, boolean force) {
        Opening.Fulfillment action = opening.fulfillments.get(index);
        DeliveryRules.Step step = DeliveryRules.step(action, DeliveryRules.replaySafe(reward), force);
        if (step == DeliveryRules.Step.SETTLED) {
            return CompletableFuture.completedFuture(Opening.Fulfillment.PENDING_INBOX.equals(action.state)
                    ? Status.PENDING_INBOX : Status.DELIVERED);
        }
        if (step == DeliveryRules.Step.REVIEW) {
            return mark(opening, index, Opening.Fulfillment.REVIEW,
                    Opening.Fulfillment.ATTEMPTED.equals(action.state)
                            ? "interrupted after it started; outcome unknown" : action.detail)
                    .thenApply(ignored -> Status.REVIEW);
        }
        String type = reward.type == null ? "" : reward.type.toUpperCase(Locale.ROOT);
        return mark(opening, index, Opening.Fulfillment.ATTEMPTED, null).thenCompose(ignored -> switch (type) {
            case "ITEM" -> deliverItem(opening, index, reward, player);
            case "ECONOMY" -> deliverEconomy(opening, index, reward, player);
            case "COMMAND" -> deliverCommand(opening, index, reward, player);
            case "PERMISSION" -> deliverPermission(opening, index, reward, player);
            default -> mark(opening, index, Opening.Fulfillment.REVIEW, "unknown reward type " + reward.type)
                    .thenApply(done -> Status.REVIEW);
        });
    }

    private CompletableFuture<Status> deliverItem(Opening opening, int index, LootBoxDraft.RewardSpec reward,
            UUID player) {
        ItemStack stack;
        try {
            stack = InventoryAdapter.stack(reward.item.itemId, reward.item.quantity, reward.item.metadata);
        } catch (RuntimeException e) {
            return mark(opening, index, Opening.Fulfillment.REVIEW, "cannot build item: " + e.getMessage())
                    .thenApply(done -> Status.REVIEW);
        }
        PlayerRef online = core.platform().findPlayer(player).orElse(null);
        if (online == null) {
            return toInbox(opening, index, player, stack, reward.name);
        }
        CompletableFuture<ItemStack> leftover = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(online, (store, entity, world) -> {
            try {
                leftover.complete(InventoryAdapter.give(store, entity, stack));
            } catch (Throwable t) {
                leftover.completeExceptionally(t);
            }
        });
        if (!dispatched) {
            return toInbox(opening, index, player, stack, reward.name);
        }
        return leftover.handle((remainder, error) -> {
            if (error != null) {
                return mark(opening, index, Opening.Fulfillment.REVIEW, "give failed: " + error)
                        .thenApply(done -> Status.REVIEW);
            }
            if (remainder != null) {
                return toInbox(opening, index, player, remainder, reward.name);
            }
            return mark(opening, index, Opening.Fulfillment.DELIVERED, null).thenApply(done -> Status.DELIVERED);
        }).thenCompose(future -> future);
    }

    private CompletableFuture<Status> toInbox(Opening opening, int index, UUID player, ItemStack stack, String label) {
        String fulfillmentId = opening.fulfillments.get(index).id;
        long now = System.currentTimeMillis();
        return storage.updatePending(player, inbox -> {
            if (inbox.contains(fulfillmentId)) {
                return null;
            }
            PendingInbox.PendingReward entry = new PendingInbox.PendingReward();
            entry.fulfillmentId = fulfillmentId;
            entry.openingId = opening.openingId;
            entry.label = label;
            entry.itemId = stack.getItemId();
            entry.quantity = stack.getQuantity();
            entry.metadata = ItemStackMetadata.toJson(stack);
            entry.createdAt = now;
            entry.expiresAt = config.rewards.pendingExpiryDays <= 0 ? 0
                    : now + Duration.ofDays(config.rewards.pendingExpiryDays).toMillis();
            inbox.items.add(entry);
            return inbox;
        }).thenCompose(inbox -> mark(opening, index, Opening.Fulfillment.PENDING_INBOX, null))
                .thenApply(done -> {
                    core.platform().findPlayer(player).ifPresent(online -> core.getMessageService()
                            .sendKey(online, "lootbox-reward-pending", Map.of("reward", plain(label))));
                    return Status.PENDING_INBOX;
                });
    }

    private CompletableFuture<Status> deliverEconomy(Opening opening, int index, LootBoxDraft.RewardSpec reward,
            UUID player) {
        boolean ok;
        try {
            ok = core.getEconomyService().isAvailable() && core.getEconomyService().deposit(player, reward.amount);
        } catch (Throwable t) {
            ok = false;
        }
        return ok ? mark(opening, index, Opening.Fulfillment.DELIVERED, null).thenApply(done -> Status.DELIVERED)
                : mark(opening, index, Opening.Fulfillment.REVIEW, "economy deposit of " + reward.amount + " failed")
                        .thenApply(done -> Status.REVIEW);
    }

    private CompletableFuture<Status> deliverCommand(Opening opening, int index, LootBoxDraft.RewardSpec reward,
            UUID player) {
        String pattern = null;
        for (LootBoxConfig.CommandPattern candidate : config.rewards.commandAllowlist) {
            if (candidate != null && candidate.id != null && candidate.id.equals(reward.allowedPatternId)) {
                pattern = candidate.pattern;
            }
        }
        int quantity = reward.item == null ? 1 : Math.max(1, reward.item.quantity);
        String rendered = CommandTemplate.render(reward.command, player.toString(), opening.playerName, quantity,
                opening.openingId);
        if (pattern == null || !CommandTemplate.allowed(rendered, pattern)) {
            audit.record("server", "COMMAND_REFUSED", opening.openingId, rendered);
            return mark(opening, index, Opening.Fulfillment.REVIEW, "rendered command is not allowlisted: " + rendered)
                    .thenApply(done -> Status.REVIEW);
        }
        boolean ok = core.platform().dispatchConsoleCommand(rendered);
        audit.record("server", "COMMAND_RUN", opening.openingId, rendered + (ok ? "" : " (dispatch failed)"));
        return ok ? mark(opening, index, Opening.Fulfillment.DELIVERED, rendered).thenApply(done -> Status.DELIVERED)
                : mark(opening, index, Opening.Fulfillment.REVIEW, "console dispatch failed: " + rendered)
                        .thenApply(done -> Status.REVIEW);
    }

    private CompletableFuture<Status> deliverPermission(Opening opening, int index, LootBoxDraft.RewardSpec reward,
            UUID player) {
        Duration duration = reward.durationSeconds <= 0 ? null : Duration.ofSeconds(reward.durationSeconds);
        return core.getPermissionService().grant(player, reward.node, duration).thenCompose(ok -> ok
                ? mark(opening, index, Opening.Fulfillment.DELIVERED, reward.node).thenApply(done -> Status.DELIVERED)
                : mark(opening, index, Opening.Fulfillment.REVIEW, "LuckPerms grant of " + reward.node + " failed")
                        .thenApply(done -> Status.REVIEW));
    }

    private CompletableFuture<Void> mark(Opening opening, int index, String state, String detail) {
        Opening.Fulfillment local = opening.fulfillments.get(index);
        local.state = state;
        local.detail = detail;
        local.updatedAt = System.currentTimeMillis();
        if (Opening.Fulfillment.ATTEMPTED.equals(state)) {
            local.attempts++;
        }
        UUID player = UUID.fromString(opening.player);
        if (Opening.Fulfillment.REVIEW.equals(state)) {
            audit.record("server", "DELIVERY_REVIEW", local.id, detail);
            core.getEventBus().publish(new RewardDeliveryFailedEvent(opening.openingId, local.id, player, local.type,
                    detail == null ? "" : detail));
        } else if (Opening.Fulfillment.DELIVERED.equals(state) || Opening.Fulfillment.PENDING_INBOX.equals(state)) {
            audit.record("server", "DELIVERED", local.id, state);
            core.getEventBus().publish(new RewardDeliveryCompletedEvent(opening.openingId, local.id, player,
                    local.type, state));
        } else if (Opening.Fulfillment.ATTEMPTED.equals(state)) {
            audit.record("server", "DELIVERY_ATTEMPT", local.id, "attempt " + local.attempts);
        }
        return storage.updateOpening(opening.openingId, stored -> {
            if (stored == null || index >= stored.fulfillments.size()) {
                return null;
            }
            Opening.Fulfillment action = stored.fulfillments.get(index);
            action.state = local.state;
            action.detail = local.detail;
            action.updatedAt = local.updatedAt;
            action.attempts = local.attempts;
            stored.state = Opening.DELIVERING;
            return stored;
        }).thenApply(stored -> null);
    }

    private CompletableFuture<Status> settle(Opening opening, UUID player, Status status) {
        String finalState = status == Status.REVIEW ? Opening.REVIEW : Opening.DELIVERED;
        opening.state = finalState;
        return storage.updateOpening(opening.openingId, stored -> {
            if (stored == null) {
                return null;
            }
            stored.state = finalState;
            return stored;
        }).thenCompose(stored -> storage.updatePlayer(player, state -> {
            DeliveryRules.settle(state, opening.openingId, status.label());
            return state;
        })).thenApply(state -> status);
    }

    private static Status worse(Status a, Status b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static String plain(String text) {
        return org.hyzionstudios.mysticessentials.core.message.MysticText.stripMarkup(text == null ? "" : text);
    }

    /** Logs a delivery chain that failed outright, so nothing disappears silently. */
    public Void logFailure(Opening opening, Throwable error) {
        core.log(Level.SEVERE, "[lootbox] delivery of " + opening.openingId + " failed: " + error);
        return null;
    }
}
