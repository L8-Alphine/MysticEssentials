package org.hyzionstudios.mysticessentials.modules.inventory;

import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Takes the Death snapshot when a player's {@link DeathComponent} is added,
 * ordered before {@code DeathSystems.DropPlayerDeathItems}: that engine system
 * drops (and removes from the inventory) the items lost on death in the same
 * callback, so anything observed later only shows what was left.
 *
 * <p>Runs on the player's world thread. The system is registered once for the
 * lifetime of the plugin and asks the module whether it is currently active —
 * the entity-store registry rejects re-registering the same system class.</p>
 */
public final class DeathSnapshotSystem extends RefChangeSystem<EntityStore, DeathComponent> {

    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.BEFORE, DeathSystems.DropPlayerDeathItems.class));

    private final InventoryModule module;

    DeathSnapshotSystem(InventoryModule module) {
        this.module = module;
    }

    @Nonnull
    @Override
    public ComponentType<EntityStore, DeathComponent> componentType() {
        return DeathComponent.getComponentType();
    }

    /** Only players have inventories worth a snapshot. */
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
        if (player != null) {
            module.onDeath(player);
        }
    }

    @Override
    public void onComponentSet(@Nonnull Ref<EntityStore> ref, DeathComponent oldComponent,
            @Nonnull DeathComponent newComponent, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
    }

    @Override
    public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
    }
}
