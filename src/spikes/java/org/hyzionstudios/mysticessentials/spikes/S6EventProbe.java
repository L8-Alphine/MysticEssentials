package org.hyzionstudios.mysticessentials.spikes;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.EnvironmentBreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * S6 (and the S5 explosion half): which block events reach a mod, for which
 * blocks, held items and game modes. Every probe logs; while a player is in
 * selection mode a hit or a break also captures the block and is cancelled, which
 * is the "hit to select" crate tool under test.
 *
 * <p>The use probe doubles as the S2 dispatcher: a {@code UseBlockEvent.Pre} on a
 * block the S2 spike swapped is cancelled exactly as the real crate system will.</p>
 */
final class S6EventProbe {

    private static final long SELECT_WINDOW_MS = 60_000L;

    private final SpikeLog log;
    private final S2VariantSpike variants;
    private final Map<UUID, Long> selecting = new ConcurrentHashMap<>();
    private volatile boolean verbose = true;
    /** When true the probe lets a swapped variant's own Use run, to show its root interaction resolves. */
    private volatile boolean letVariantUseRun;

    S6EventProbe(SpikeLog log, S2VariantSpike variants) {
        this.log = log;
        this.variants = variants;
    }

    /** The systems to register once, for the plugin's lifetime. */
    List<ISystem<EntityStore>> systems() {
        return List.of(new UseProbe(), new DamageProbe(), new BreakProbe(),
                new WorldDamageProbe(), new EnvironmentBreakProbe());
    }

    void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    void setLetVariantUseRun(boolean letRun) {
        this.letVariantUseRun = letRun;
    }

    void startSelecting(PlayerRef player) {
        selecting.put(player.getUuid(), System.currentTimeMillis() + SELECT_WINDOW_MS);
    }

    private boolean isSelecting(PlayerRef player) {
        if (player == null) {
            return false;
        }
        Long until = selecting.get(player.getUuid());
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            selecting.remove(player.getUuid());
            return false;
        }
        return true;
    }

    private void captured(PlayerRef player, String how, String blockId, Vector3i at, String context) {
        String where = at.x + "," + at.y + "," + at.z;
        log.line("S6", "PASS selection by " + how + " captured " + blockId + " at " + where + " (" + context + ")");
        player.sendMessage(Message.raw("[LBSpike S6] Selected by " + how + ": " + blockId + " at " + where));
    }

    private static String name(PlayerRef player) {
        return player == null ? "a non-player entity" : player.getUsername();
    }

    private static String held(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "empty hand" : stack.getItemId();
    }

    private static String gameMode(Store<EntityStore> store, Ref<EntityStore> ref) {
        Player player = store.getComponent(ref, Player.getComponentType());
        return player == null ? "-" : String.valueOf(player.getGameMode());
    }

    // ----- Probes -------------------------------------------------------------

    private final class UseProbe extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {

        UseProbe() {
            super(UseBlockEvent.Pre.class);
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull UseBlockEvent.Pre event) {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            Vector3i at = event.getTargetBlock();
            String world = store.getExternalData().getWorld().getName();
            S2VariantSpike.Swap swap = variants.swapAt(world, at.x, at.y, at.z);
            if (swap != null && event.getInteractionType() == InteractionType.Use) {
                if (letVariantUseRun) {
                    log.line("S2", "UseBlockEvent.Pre fired for variant " + event.getBlockType().getId() + " at "
                            + swap.position() + "; letting its own Use run");
                    return;
                }
                event.setCancelled(true);
                log.line("S2", "PASS UseBlockEvent.Pre fired for variant " + event.getBlockType().getId() + " at "
                        + swap.position() + " by " + name(player) + "; cancelled where a crate would open");
                if (player != null) {
                    player.sendMessage(Message.raw("[LBSpike S2] Pre fired for the variant and was cancelled:"
                            + " this is where the crate preview would open."));
                }
                return;
            }
            if (verbose) {
                log.line("S6", "UseBlockEvent.Pre type=" + event.getInteractionType() + " block="
                        + event.getBlockType().getId() + " at " + at.x + "," + at.y + "," + at.z + " by "
                        + name(player));
            }
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }

    private final class DamageProbe extends EntityEventSystem<EntityStore, DamageBlockEvent> {

        DamageProbe() {
            super(DamageBlockEvent.class);
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull DamageBlockEvent event) {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            Vector3i at = event.getTargetBlock();
            String context = "gameMode=" + gameMode(store, ref) + ", held=" + held(event.getItemInHand())
                    + ", damage=" + event.getDamage() + ", current=" + event.getCurrentDamage();
            if (isSelecting(player)) {
                event.setCancelled(true);
                captured(player, "hit (DamageBlockEvent)", event.getBlockType().getId(), at, context);
                return;
            }
            if (verbose) {
                log.line("S6", "DamageBlockEvent (entity) block=" + event.getBlockType().getId() + " at " + at.x
                        + "," + at.y + "," + at.z + " by " + name(player) + " " + context);
            }
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }

    private final class BreakProbe extends EntityEventSystem<EntityStore, BreakBlockEvent> {

        BreakProbe() {
            super(BreakBlockEvent.class);
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull BreakBlockEvent event) {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            Vector3i at = event.getTargetBlock();
            String context = "gameMode=" + gameMode(store, ref) + ", held=" + held(event.getItemInHand());
            if (isSelecting(player)) {
                event.setCancelled(true);
                captured(player, "break (BreakBlockEvent)", event.getBlockType().getId(), at, context);
                return;
            }
            if (verbose) {
                log.line("S6", "BreakBlockEvent (entity) block=" + event.getBlockType().getId() + " at " + at.x
                        + "," + at.y + "," + at.z + " by " + name(player) + " " + context);
            }
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }
    }

    /** Damage with no instigating entity (explosions): the S5 question. */
    private final class WorldDamageProbe extends WorldEventSystem<EntityStore, DamageBlockEvent> {

        WorldDamageProbe() {
            super(DamageBlockEvent.class);
        }

        @Override
        public void handle(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull DamageBlockEvent event) {
            Vector3i at = event.getTargetBlock();
            log.line("S5", "DamageBlockEvent (world, no instigator) block=" + event.getBlockType().getId() + " at "
                    + at.x + "," + at.y + "," + at.z + " damage=" + event.getDamage()
                    + " - cancellable before any damage");
        }
    }

    private final class EnvironmentBreakProbe extends WorldEventSystem<EntityStore, EnvironmentBreakBlockEvent> {

        EnvironmentBreakProbe() {
            super(EnvironmentBreakBlockEvent.class);
        }

        @Override
        public void handle(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull EnvironmentBreakBlockEvent event) {
            Vector3i at = event.getTargetBlock();
            log.line("S5", "EnvironmentBreakBlockEvent block=" + event.getBlockType().getId() + " at " + at.x + ","
                    + at.y + "," + at.z + " - not cancellable, block already decided");
        }
    }
}
