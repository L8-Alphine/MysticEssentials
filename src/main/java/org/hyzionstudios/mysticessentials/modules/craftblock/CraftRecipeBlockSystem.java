package org.hyzionstudios.mysticessentials.modules.craftblock;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Cancels blocked crafts at the only point the engine offers a veto:
 * {@code CraftRecipeEvent.Pre}, which {@code CraftingManager} invokes on the
 * crafting player's entity before it touches the inventory. Both the instant
 * path ({@code craftItem}) and the timed bench path ({@code queueCraft}) fire
 * it, and both abort when it comes back cancelled — nothing is consumed and
 * nothing is produced.
 *
 * <p>Runs on the player's world thread, so it must stay cheap: the decision is
 * a couple of lookups against a pre-compiled {@link CraftBlockRules} snapshot.</p>
 *
 * <p>The system is registered once for the lifetime of the plugin and asks the
 * module whether it is currently active — the entity-store registry rejects
 * re-registering the same system class, so hot-disabling the module makes this
 * inert rather than removing it.</p>
 */
public final class CraftRecipeBlockSystem extends EntityEventSystem<EntityStore, CraftRecipeEvent.Pre> {

    private final CraftBlockModule module;

    CraftRecipeBlockSystem(CraftBlockModule module) {
        super(CraftRecipeEvent.Pre.class);
        this.module = module;
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull CraftRecipeEvent.Pre event) {
        PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
        String blocked = module.blockedIdFor(player, event.getCraftedRecipe());
        if (blocked == null) {
            return;
        }
        event.setCancelled(true);
        module.onCraftDenied(player, blocked);
    }

    /** Only player entities craft; this keeps the system off every other archetype. */
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }
}
