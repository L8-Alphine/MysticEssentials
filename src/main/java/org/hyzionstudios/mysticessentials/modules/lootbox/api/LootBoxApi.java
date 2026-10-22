package org.hyzionstudios.mysticessentials.modules.lootbox.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.IntegrationRegistry;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.KeyService;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The LootBox surface other modules and addons use, resolvable through
 * {@code getModuleManager().getModule("lootbox")}. Odds are only ever changed by
 * publishing; nothing here can alter a pool or a committed opening.
 */
public interface LootBoxApi {

    /** The pool's active published revision, if it has one. */
    Optional<LootBoxRevision> activeRevision(String lootboxId);

    /**
     * Issues keys (a quest reward, an event drop). {@code source} is recorded and
     * checked against the policy profile: {@link GrantSource#PAID} is refused on
     * {@code FREE_GAMEPLAY_ONLY} servers.
     */
    CompletableFuture<KeyService.GrantResult> grantKey(UUID player, String keyId, int count, boolean virtual,
            GrantSource source, String actor);

    /** The player's receipts, newest first. */
    CompletableFuture<List<PlayerLootState.Receipt>> receipts(UUID player);

    void openPreview(PlayerRef player, String lootboxId);

    // ----- Providers (P3-WP1) -------------------------------------------------

    /** The registered providers; {@code missing(spec)} says what a pool lacks. */
    IntegrationRegistry integrations();

    /**
     * Adds (or replaces, by id) a reward provider. Register when your plugin
     * starts; it survives LootBox being disabled and enabled again. Pools whose
     * rewards name it become openable once it is present and published.
     */
    void registerRewardProvider(RewardProvider provider);

    /**
     * Removes {@code provider} (only if it is still the registered one). Pools
     * that use it are refused from then on; their receipts and pending deliveries
     * stay, and an interrupted delivery goes to review. Nothing is substituted.
     */
    boolean unregisterRewardProvider(RewardProvider provider);

    void registerConditionProvider(ConditionProvider provider);

    boolean unregisterConditionProvider(ConditionProvider provider);

    void openCatalog(PlayerRef player);
}
