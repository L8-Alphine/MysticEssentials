package org.hyzionstudios.mysticessentials.modules.portals;

import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Drops a portal (and its map marker) when its anchor block is broken.
 * {@code BreakBlockEvent} is an ECS event: {@code BlockHarvestUtils} invokes it
 * on the breaking entity through the entity store, so it only ever reaches
 * entity event systems, never a world or plugin event registry.
 *
 * <p>Runs on the breaking entity's world thread, after every other system, so
 * a break another system cancels (protection, trigger volumes) never removes
 * the portal — cancelled events are not delivered to later systems.</p>
 *
 * <p>The system is registered once for the lifetime of the plugin and asks for
 * the live module — the entity-store registry rejects re-registering the same
 * system class, so hot-disabling the module makes this inert rather than
 * removing it.</p>
 */
public final class PortalBreakSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    PortalBreakSystem() {
        super(BreakBlockEvent.class);
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull BreakBlockEvent event) {
        PortalsModule module = PortalsModule.active();
        if (module == null) {
            return;
        }
        module.onBreakBlock(store.getExternalData().getWorld(), event);
    }

    /** Players and NPCs alike can break an anchor block. */
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return RootDependency.lastSet();
    }
}
