package org.hyzionstudios.mysticessentials.modules.lootbox.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.event.EventBus;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.ConditionProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.GrantSource;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.LootBoxApi;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.KeyGrantedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningCommittedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningFlaggedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.PendingRewardClaimedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.AuditService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.IntegrationRegistry;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Wires the P3-WP7 bridges into LootBox on enable and takes them out on
 * disable. Every Mystic sibling is optional and found by class presence; none
 * is a dependency, and no other repo is changed. What each one needs from its
 * owner to do better is in {@code docs/lootbox/implementation-plan.md}.
 *
 * <p>MysticGuilds (group eligibility) and MysticGeneration (NPC and event
 * rewards) have no bridge: Guilds exposes no membership API to ask, and
 * Generation calls LootBox ({@link LootBoxApi#grantKey}) rather than the other
 * way round.</p>
 */
public final class LootBoxIntegrations {

    private final MysticCore core;
    private final IntegrationRegistry registry;
    private final Supplier<LootBoxConfig> config;
    private final LootBoxPlaceholders placeholders = new LootBoxPlaceholders();
    private final List<RewardProvider> ownRewards = new ArrayList<>();
    private final List<ConditionProvider> ownConditions = new ArrayList<>();
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    private final List<String> status = new ArrayList<>();
    private MysticQuestsBridge quests;
    private ModerationForwarder moderation;
    private IdentityPaidGate paidGate;

    public LootBoxIntegrations(MysticCore core, IntegrationRegistry registry, Supplier<LootBoxConfig> config) {
        this.core = core;
        this.registry = registry;
        this.config = config;
    }

    /**
     * Detects and registers what is installed. {@code api} grants quest keys;
     * {@code keyIds} are the published keys, for the placeholders.
     */
    public synchronized void enable(LootBoxApi api, LootBoxStorage storage, AuditService audit,
            Supplier<java.util.Collection<String>> keyIds) {
        disable();
        LootBoxConfig.Integrations settings = settings();
        BiConsumer<String, Throwable> log = (message, error) -> core.log(error == null ? Level.INFO : Level.WARNING,
                "[lootbox] " + message + (error == null ? "" : ": " + error));
        Function<UUID, String> names = player -> core.platform().findPlayer(player).map(PlayerRef::getUsername)
                .orElse(null);

        paidGate = IdentityPaidGate.create(() -> settings().identity.requireAgeSignal);
        status.add("MysticIdentity paid-grant gate: " + (paidGate.present() ? "detected" : "not installed (paid grants refused)"));

        if (settings.mysticEconomy) {
            MysticEconomyRewards economy = MysticEconomyRewards.detect(names,
                    () -> Math.max(10, settings().economyReconcileDepth));
            own(economy, "MysticEconomy rewards (provider mysticeconomy)");
        } else {
            status.add("MysticEconomy rewards: off in config");
        }

        if (settings.mysticQuests) {
            quests = MysticQuestsBridge.detect((player, grant, questPackage) -> api.grantKey(player, grant.keyId(),
                    grant.count(), grant.virtual(), GrantSource.QUEST, "quest:" + questPackage)
                    .thenAccept(result -> {
                        if (!result.ok()) {
                            log.accept("quest key grant of " + grant.keyId() + " to " + player + " failed: "
                                    + result.problem(), null);
                        }
                    }), () -> config.get().keys.maxGrantPerCommand, log);
            if (quests != null) {
                own(quests.condition(), "MysticQuests condition QUEST_COMPLETED");
                quests.attach();
            } else {
                status.add("MysticQuests: not installed");
            }
        } else {
            status.add("MysticQuests: off in config");
        }

        if (settings.mysticRpg) {
            MysticRpgBridge rpg = MysticRpgBridge.detect();
            if (rpg != null) {
                own(rpg.level(), "MysticRPG condition RPG_LEVEL");
                own(rpg.rpgClass(), "MysticRPG condition RPG_CLASS");
                own(rpg.experience(), "MysticRPG rewards (provider mysticrpg)");
            } else {
                status.add("MysticRPG: not installed (no 0.7 build yet)");
            }
        } else {
            status.add("MysticRPG: off in config");
        }

        if (settings.permissionCosmetics && core.getPermissionService().canGrant()) {
            own(new PermissionCosmetics(core.getPermissionService()), "COSMETIC provider permission (LuckPerms)");
        } else {
            status.add("COSMETIC provider permission: " + (settings.permissionCosmetics ? "needs LuckPerms" : "off"));
        }

        if (settings.mysticModeration) {
            moderation = ModerationForwarder.detect(names,
                    () -> core.redis() == null ? "local" : core.redis().serverId(), log);
            if (moderation != null) {
                subscriptions.add(core.getEventBus().subscribe(OpeningFlaggedEvent.class, event -> {
                    ModerationForwarder forwarder = moderation;
                    if (forwarder == null) {
                        return;
                    }
                    long cooldown = TimeUnit.MINUTES.toMillis(Math.max(0, settings().flagCooldownMinutes));
                    CompletableFuture.supplyAsync(() -> forwarder.forward(event, System.currentTimeMillis(), cooldown))
                            .thenAccept(outcome -> {
                                if (!"THROTTLED".equals(outcome) && !"NO_PLAYER".equals(outcome)) {
                                    audit.record("lootbox", "FLAG_FORWARDED", String.valueOf(event.player()),
                                            event.lootboxId() + " " + event.reason() + " -> " + outcome);
                                }
                            });
                }));
                status.add("MysticModeration: flags forwarded as reports");
            } else {
                status.add("MysticModeration: not installed");
            }
        } else {
            status.add("MysticModeration: off in config");
        }

        if (settings.placeholders) {
            placeholders.use(storage);
            placeholders.register(core.getPlaceholderService()::register, keyIds.get());
            subscriptions.add(core.getEventBus().subscribe(KeyGrantedEvent.class,
                    event -> placeholders.refresh(event.player())));
            subscriptions.add(core.getEventBus().subscribe(OpeningCommittedEvent.class,
                    event -> placeholders.refresh(event.player())));
            subscriptions.add(core.getEventBus().subscribe(PendingRewardClaimedEvent.class,
                    event -> placeholders.refresh(event.player())));
            for (PlayerRef player : core.platform().onlinePlayers()) {
                placeholders.refresh(player.getUuid());
            }
            status.add("Placeholders: lootbox_keys, lootbox_keys_<ns>_<name>, lootbox_pending");
        }
        status.add("MysticGuilds: no membership API to ask yet (GUILD condition pending)");
        status.add("MysticGeneration: grants keys through LootBoxApi.grantKey; nothing to register here");
    }

    /** Periodic: registers the quest event once MysticQuests is up (it may start after this plugin). */
    public synchronized void tick() {
        if (quests != null) {
            quests.attach();
        }
    }

    /** New keys were published: their placeholders. */
    public synchronized void keysChanged(java.util.Collection<String> keyIds) {
        if (settings().placeholders) {
            placeholders.register(core.getPlaceholderService()::register, keyIds);
        }
    }

    public void playerJoined(UUID player) {
        placeholders.refresh(player);
    }

    public void playerLeft(UUID player) {
        placeholders.forget(player);
    }

    /**
     * {@code null} when {@code player} may receive a PAID key grant, otherwise why
     * not. Fails closed while LootBox is off or MysticIdentity is missing.
     */
    public String paidRefusal(UUID player) {
        IdentityPaidGate gate = paidGate;
        return gate == null ? "LootBox integrations are not running" : gate.apply(player);
    }

    public synchronized void disable() {
        for (EventBus.Subscription subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // Nothing to undo.
            }
        }
        subscriptions.clear();
        if (quests != null) {
            quests.detach();
            quests = null;
        }
        for (RewardProvider provider : ownRewards) {
            registry.unregister(provider);
        }
        for (ConditionProvider provider : ownConditions) {
            registry.unregister(provider);
        }
        ownRewards.clear();
        ownConditions.clear();
        moderation = null;
        paidGate = null;
        placeholders.use(null);
        status.clear();
    }

    /** One line per integration, for {@code /lootbox providers}. */
    public synchronized List<String> status() {
        return List.copyOf(status);
    }

    private void own(RewardProvider provider, String label) {
        if (provider == null) {
            status.add(label + ": not installed");
            return;
        }
        try {
            registry.register(provider);
            ownRewards.add(provider);
            status.add(label + ": registered");
        } catch (IllegalArgumentException e) {
            status.add(label + ": refused (" + e.getMessage() + ")");
        }
    }

    private void own(ConditionProvider provider, String label) {
        try {
            registry.register(provider);
            ownConditions.add(provider);
            status.add(label + ": registered");
        } catch (IllegalArgumentException e) {
            status.add(label + ": refused (" + e.getMessage() + ")");
        }
    }

    private LootBoxConfig.Integrations settings() {
        LootBoxConfig current = config.get();
        return current == null || current.integrations == null ? new LootBoxConfig.Integrations()
                : current.integrations;
    }
}
