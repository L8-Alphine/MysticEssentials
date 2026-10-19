package org.hyzionstudios.mysticessentials.modules.lootbox.hytale;

import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.joml.Vector3i;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.EnvironmentBreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The ECS systems behind crates. All of these events are invoked through the
 * entity store, so they only ever reach systems, never a plugin event registry.
 * The systems are registered once for the plugin's lifetime (the registry keys
 * systems by class and offers no unregister) and ask for the live module, so a
 * disabled module leaves them inert.
 *
 * <ul>
 *   <li>{@link Use}: {@code UseBlockEvent.Pre} only fires for blocks whose asset
 *       declares a Use interaction ({@code UseBlockInteraction#doInteraction}).
 *       On a crate it is cancelled, so the block's own behaviour (a chest
 *       opening) never runs, and the pool's preview opens instead.</li>
 *   <li>{@link Break} / {@link Damage}: player-caused, cancellable.</li>
 *   <li>{@link WorldDamage}: damage with no instigator (explosions) is invoked at
 *       world level and is cancellable before any damage lands.</li>
 *   <li>{@link EnvironmentBreak}: the final no-instigator break is not
 *       cancellable; the crate is disabled and staff are told.</li>
 * </ul>
 */
public final class CrateSystems {

    private CrateSystems() {
    }

    public static List<ISystem<EntityStore>> all() {
        return List.of(new Use(), new Break(), new Damage(), new WorldDamage(), new EnvironmentBreak());
    }

    private static CrateInstance crateAt(Store<EntityStore> store, Vector3i position) {
        LootBoxModule module = LootBoxModule.active();
        if (module == null || position == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        return module.crates().at(world, position.x, position.y, position.z).orElse(null);
    }

    private static PlayerRef player(ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, int index) {
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        return ref == null ? null : store.getComponent(ref, PlayerRef.getComponentType());
    }

    /** Opens the crate's pool instead of the block's own Use. */
    static final class Use extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {

        Use() {
            super(UseBlockEvent.Pre.class);
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull UseBlockEvent.Pre event) {
            if (event.getInteractionType() != InteractionType.Use) {
                return;
            }
            CrateInstance crate = crateAt(store, event.getTargetBlock());
            if (crate == null) {
                return;
            }
            event.setCancelled(true);
            PlayerRef player = player(chunk, store, index);
            LootBoxModule module = LootBoxModule.active();
            if (player != null && module != null) {
                module.onCrateUse(player, crate, event.getBlockType().getId());
            }
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }

    static final class Break extends EntityEventSystem<EntityStore, BreakBlockEvent> {

        Break() {
            super(BreakBlockEvent.class);
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull BreakBlockEvent event) {
            CrateInstance crate = crateAt(store, event.getTargetBlock());
            LootBoxModule module = LootBoxModule.active();
            if (crate == null || module == null || !module.config().crates.preventPlayerBreak) {
                return;
            }
            event.setCancelled(true);
            module.onCrateProtected(player(chunk, store, index));
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        /** After every other system, so a break another system already cancelled is never reported. */
        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return RootDependency.lastSet();
        }
    }

    static final class Damage extends EntityEventSystem<EntityStore, DamageBlockEvent> {

        Damage() {
            super(DamageBlockEvent.class);
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull DamageBlockEvent event) {
            LootBoxModule module = LootBoxModule.active();
            if (module != null && module.config().crates.preventDamage && crateAt(store, event.getTargetBlock()) != null) {
                event.setCancelled(true);
            }
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }

    static final class WorldDamage extends WorldEventSystem<EntityStore, DamageBlockEvent> {

        WorldDamage() {
            super(DamageBlockEvent.class);
        }

        @Override
        public void handle(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull DamageBlockEvent event) {
            LootBoxModule module = LootBoxModule.active();
            if (module != null && module.config().crates.preventDamage && crateAt(store, event.getTargetBlock()) != null) {
                event.setCancelled(true);
            }
        }
    }

    static final class EnvironmentBreak extends WorldEventSystem<EntityStore, EnvironmentBreakBlockEvent> {

        EnvironmentBreak() {
            super(EnvironmentBreakBlockEvent.class);
        }

        @Override
        public void handle(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull EnvironmentBreakBlockEvent event) {
            CrateInstance crate = crateAt(store, event.getTargetBlock());
            LootBoxModule module = LootBoxModule.active();
            if (crate != null && module != null) {
                module.onCrateDestroyed(crate, "destroyed by the environment (" + event.getBlockType().getId() + ")");
            }
        }
    }
}
