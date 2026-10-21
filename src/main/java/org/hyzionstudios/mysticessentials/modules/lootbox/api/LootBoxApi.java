package org.hyzionstudios.mysticessentials.modules.lootbox.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
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

    void openCatalog(PlayerRef player);
}
