package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.RewardDeliveryCompletedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.RewardDeliveryFailedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.InventoryAdapter;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
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
    private final IntegrationRegistry integrations;
    private volatile LootBoxConfig config;
    /** Openings this server is delivering, with when each delivery started. */
    private final Map<String, Long> delivering = new ConcurrentHashMap<>();

    public RewardService(MysticCore core, LootBoxStorage storage, AuditService audit, IntegrationRegistry integrations,
            LootBoxConfig config) {
        this.core = core;
        this.storage = storage;
        this.audit = audit;
        this.integrations = integrations;
        this.config = config;
    }

    public void updateConfig(LootBoxConfig config) {
        this.config = config;
    }

    /**
     * Runs every unsettled action of {@code opening}: the main outcome's actions
     * (several for a bundle) and each bonus roll's, each resolved from the
     * opening's own revision. {@code force} also re-runs actions in REVIEW
     * ({@code /lootbox reconcile … regrant}).
     */
    public CompletableFuture<Status> deliver(Opening opening, LootBoxRevision revision, boolean force) {
        UUID player = UUID.fromString(opening.player);
        // Registered before the first step: the opening's lane lets go as soon as
        // this returns, and recovery in that lane must already see it running.
        long started = System.currentTimeMillis();
        delivering.put(opening.openingId, started);
        CompletableFuture<Status> chain = CompletableFuture.completedFuture(Status.DELIVERED);
        for (int i = 0; i < opening.fulfillments.size(); i++) {
            int index = i;
            chain = chain.thenCompose(worst -> deliverOne(opening, index, revision, player, force)
                    .thenApply(status -> worse(worst, status)));
        }
        return chain.thenCompose(status -> settle(opening, player, status))
                .whenComplete((status, error) -> delivering.remove(opening.openingId, started));
    }

    /** When this server started delivering {@code openingId}, or {@code null} if it is not delivering it. */
    public Long deliveringSince(String openingId) {
        return delivering.get(openingId);
    }

    private CompletableFuture<Status> deliverOne(Opening opening, int index, LootBoxRevision revision, UUID player,
            boolean force) {
        Opening.Fulfillment action = opening.fulfillments.get(index);
        LootBoxDraft.RewardSpec reward = RewardActions.actionOf(revision, opening, action);
        if (reward == null) {
            if (action.isSettled()) {
                return CompletableFuture.completedFuture(Opening.Fulfillment.PENDING_INBOX.equals(action.state)
                        ? Status.PENDING_INBOX : Status.DELIVERED);
            }
            return mark(opening, index, Opening.Fulfillment.REVIEW, "its revision or reward is gone")
                    .thenApply(done -> Status.REVIEW);
        }
        String label = RewardActions.labelOf(revision, opening, action);
        RewardProvider.Guarantee guarantee = RewardActions.guarantee(reward, integrations::reward);
        DeliveryRules.Step step = DeliveryRules.step(action, guarantee, force);
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
        if (step == DeliveryRules.Step.RECONCILE) {
            return reconcileProvider(opening, index, reward, player);
        }
        String type = RewardActions.type(reward);
        return mark(opening, index, Opening.Fulfillment.ATTEMPTED, null).thenCompose(ignored -> {
            if (RewardActions.needsProvider(reward)) {
                return deliverProvider(opening, index, reward, player);
            }
            return switch (type) {
                case "ITEM" -> deliverItem(opening, index, reward, label, player);
                case "ECONOMY" -> deliverEconomy(opening, index, reward, player);
                case "COMMAND" -> deliverCommand(opening, index, reward, player);
                case "PERMISSION" -> deliverPermission(opening, index, reward, player);
                default -> mark(opening, index, Opening.Fulfillment.REVIEW, "unknown reward type " + reward.type)
                        .thenApply(done -> Status.REVIEW);
            };
        });
    }

    /**
     * A provider-delivered action (P3-WP1). With the provider gone it goes to
     * review: nothing else is ever delivered in its place.
     */
    private CompletableFuture<Status> deliverProvider(Opening opening, int index, LootBoxDraft.RewardSpec reward,
            UUID player) {
        RewardProvider provider = integrations.reward(reward.provider);
        if (provider == null) {
            return mark(opening, index, Opening.Fulfillment.REVIEW, "reward provider '" + reward.provider
                    + "' is not installed; nothing was substituted").thenApply(done -> Status.REVIEW);
        }
        String fulfillmentId = opening.fulfillments.get(index).id;
        CompletableFuture<RewardProvider.Result> granted;
        try {
            granted = provider.grant(fulfillmentId, player, RewardActions.providerParams(reward));
        } catch (Throwable t) {
            granted = CompletableFuture.failedFuture(t);
        }
        return granted.handle((result, error) -> settleProvider(opening, index, provider,
                error != null ? RewardProvider.Result.unknown("grant threw " + error)
                        : result == null ? RewardProvider.Result.unknown("no answer") : result))
                .thenCompose(future -> future);
    }

    /** Asks a reconcilable provider whether an interrupted action was applied. */
    private CompletableFuture<Status> reconcileProvider(Opening opening, int index, LootBoxDraft.RewardSpec reward,
            UUID player) {
        RewardProvider provider = integrations.reward(reward.provider);
        if (provider == null) {
            return mark(opening, index, Opening.Fulfillment.REVIEW, "interrupted, and reward provider '"
                    + reward.provider + "' is not installed to reconcile it").thenApply(done -> Status.REVIEW);
        }
        String fulfillmentId = opening.fulfillments.get(index).id;
        CompletableFuture<RewardProvider.Result> asked;
        try {
            asked = provider.reconcile(fulfillmentId, player, RewardActions.providerParams(reward));
        } catch (Throwable t) {
            asked = CompletableFuture.failedFuture(t);
        }
        return asked.handle((result, error) -> {
            RewardProvider.Outcome outcome = error != null || result == null ? RewardProvider.Outcome.UNKNOWN
                    : result.outcome();
            String detail = error != null ? "reconcile threw " + error : result == null ? "no answer"
                    : result.detail();
            audit.record("server", "DELIVERY_RECONCILE", fulfillmentId, outcome + " " + detail);
            return switch (outcome) {
                case DELIVERED, ALREADY_DELIVERED -> mark(opening, index, Opening.Fulfillment.DELIVERED,
                        "reconciled: " + detail).thenApply(done -> Status.DELIVERED);
                case FAILED -> deliverProvider(opening, index, reward, player);
                case UNKNOWN -> mark(opening, index, Opening.Fulfillment.REVIEW, "interrupted; the provider "
                        + "can't tell whether it was applied (" + detail + ")").thenApply(done -> Status.REVIEW);
            };
        }).thenCompose(future -> future);
    }

    private CompletableFuture<Status> settleProvider(Opening opening, int index, RewardProvider provider,
            RewardProvider.Result result) {
        String detail = provider.id() + ": " + (result.detail() == null ? "" : result.detail());
        return switch (result.outcome()) {
            case DELIVERED, ALREADY_DELIVERED -> mark(opening, index, Opening.Fulfillment.DELIVERED, detail)
                    .thenApply(done -> Status.DELIVERED);
            case FAILED -> mark(opening, index, Opening.Fulfillment.REVIEW, "not delivered: " + detail)
                    .thenApply(done -> Status.REVIEW);
            case UNKNOWN -> mark(opening, index, Opening.Fulfillment.REVIEW, "outcome unknown: " + detail)
                    .thenApply(done -> Status.REVIEW);
        };
    }

    private CompletableFuture<Status> deliverItem(Opening opening, int index, LootBoxDraft.RewardSpec reward,
            String label, UUID player) {
        ItemStack stack;
        try {
            stack = InventoryAdapter.stack(reward.item.itemId, reward.item.quantity, reward.item.metadata);
        } catch (RuntimeException e) {
            return mark(opening, index, Opening.Fulfillment.REVIEW, "cannot build item: " + e.getMessage())
                    .thenApply(done -> Status.REVIEW);
        }
        PlayerRef online = core.platform().findPlayer(player).orElse(null);
        if (online == null) {
            return toInbox(opening, index, player, stack, label);
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
            return toInbox(opening, index, player, stack, label);
        }
        return leftover.handle((remainder, error) -> {
            if (error != null) {
                return mark(opening, index, Opening.Fulfillment.REVIEW, "give failed: " + error)
                        .thenApply(done -> Status.REVIEW);
            }
            if (remainder != null) {
                return toInbox(opening, index, player, remainder, label);
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
