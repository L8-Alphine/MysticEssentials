package org.hyzionstudios.mysticessentials.modules.shell;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * ALT+1 … ALT+9 for the shortcut rail.
 *
 * <h2>How a server-side mod sees a key chord</h2>
 *
 * <p>There is no keybind API, but there does not need to be: both halves of the
 * chord are keys the client already binds to something it reports. Alt is
 * Hytale's default <em>walk</em> key, so holding it sets
 * {@link MovementStates#walking}; a digit selects a hotbar slot, which raises
 * {@link InventorySetActiveSlotEvent}. A slot change that lands while walking is
 * an ALT+digit press. (Endgame &amp; QoL does the same for one digit; this binds
 * all nine.)</p>
 *
 * <p>The press must not cost the player their held item, so the slot is put back
 * — on the server and, with a {@link SetActiveSlot} packet, on the client.</p>
 *
 * <h2>Telling a chord from ordinary play</h2>
 *
 * <ul>
 *   <li><b>Latch.</b> Alt counts as held while walking and for {@value #LATCH_MS} ms
 *       after release, so letting go a hair before the digit still works.</li>
 *   <li><b>Scroll wheel.</b> A move to a neighbouring slot (including 9↔1) could be
 *       the mouse wheel, so it only counts when Alt went down in the last
 *       {@value #FRESH_MS} ms — someone who has been walking for a while and scrolls
 *       is just scrolling.</li>
 *   <li><b>Packet order.</b> Pressed together, the slot can reach the server before
 *       the walk state. A non-neighbouring move is parked and honoured if walking
 *       starts within {@value #CHORD_MS} ms. Neighbouring moves are never parked, so
 *       the wheel can never trigger a shortcut by this route.</li>
 *   <li><b>Our own restore.</b> {@code setActiveSlot} raises the same event. With
 *       nine digits bound, putting the player back on slot 3 while Alt is still
 *       latched would read as ALT+3 and fire again — then restore again, forever.
 *       The restore is marked and its echo ignored.</li>
 * </ul>
 *
 * <h2>Sharing the chord with another mod</h2>
 *
 * <p>A digit another installed mod binds the same way ({@link ModHotkeys}) is
 * left to it, and so is a digit the server turned off. Two more steps keep
 * the two mods from setting each other off:</p>
 *
 * <ul>
 *   <li><b>Its restore.</b> The other mod puts the hotbar back too, raising the
 *       same event while Alt is still latched; to this class that would read as
 *       ALT+(the slot it went back to). It is ignored like our own echo.</li>
 *   <li><b>Our restore.</b> Putting a player back <em>onto</em> the other mod's
 *       digit while Alt is down would read to it as its chord. That restore
 *       waits until Alt has been up for longer than a latch.</li>
 * </ul>
 *
 * <h2>Cost on the world thread</h2>
 *
 * <p>The tick system reads one boolean per player per tick and touches the map
 * only when walking starts or stops; nothing is allocated on a quiet tick. The
 * shortcut itself runs from {@code World#execute}, outside the systems'
 * iteration, because it can open a page and the restore raises an event.</p>
 */
final class AltShortcuts {

    /** Alt still counts as held this long after the walk key is released. */
    private static final long LATCH_MS = 800;
    /** A neighbouring-slot move needs an Alt press at most this old (it may be the wheel). */
    private static final long FRESH_MS = 350;
    /** A parked digit is honoured when walking starts within this window. */
    private static final long CHORD_MS = 250;
    /** How long our own restore's echo is expected. */
    private static final long ECHO_MS = 1000;
    /** A held-back restore waits this long past the latch, for the other mod's tick to see Alt go up. */
    private static final long RESTORE_MARGIN_MS = 250;
    /** Shortcuts are numbered 1–9, whatever the hotbar's capacity. */
    private static final int SLOTS = 9;

    /** Per player and mutable: written only on a walk transition, so a quiet tick allocates nothing. */
    private static final class Walk {
        boolean walking;
        long start;
        long end;
    }

    private record Chord(byte slot, byte previous, long at) {
    }

    private record Echo(byte slot, long until) {
    }

    /** A restore held back until Alt is up: from the shortcut's slot back to {@code to}. */
    private record Restore(byte from, byte to) {
    }

    private final ShellModule shell;
    private final Map<UUID, Walk> walks = new ConcurrentHashMap<>();
    private final Map<UUID, Chord> chords = new ConcurrentHashMap<>();
    private final Map<UUID, Echo> echoes = new ConcurrentHashMap<>();
    private final Map<UUID, Restore> restores = new ConcurrentHashMap<>();

    AltShortcuts(ShellModule shell) {
        this.shell = shell;
    }

    EntityTickingSystem<EntityStore> walkSystem() {
        return new WalkSystem();
    }

    EntityEventSystem<EntityStore, InventorySetActiveSlotEvent> slotSystem() {
        return new SlotSystem();
    }

    /** Drops a player's state on disconnect. */
    void forget(UUID player) {
        walks.remove(player);
        chords.remove(player);
        echoes.remove(player);
        restores.remove(player);
    }

    // ----- Walk state ---------------------------------------------------------

    /**
     * Records the player's walk state.
     *
     * @return true exactly when walking has just started
     */
    private boolean observe(UUID player, MovementStates states, long now) {
        // Running and sprinting override the walk key, so they end a walk.
        boolean walkingNow = states != null && states.walking && !states.running && !states.sprinting;
        Walk walk = walks.get(player);
        if (walk == null) {
            if (!walkingNow) {
                return false;
            }
            walk = new Walk();
            walks.put(player, walk);
        }
        if (walk.walking == walkingNow) {
            // Keep only recent walkers in the map: a long-released walk can no
            // longer latch anything. A held-back restore still needs the
            // release time, so its walker stays until the restore is done.
            if (!walkingNow && now - walk.end > LATCH_MS && !restores.containsKey(player)) {
                walks.remove(player);
            }
            return false;
        }
        walk.walking = walkingNow;
        if (walkingNow) {
            walk.start = now;
            return true;
        }
        walk.end = now;
        return false;
    }

    private boolean altHeld(UUID player, boolean needFresh, long now) {
        Walk walk = walks.get(player);
        if (walk == null || !(walk.walking || now - walk.end < LATCH_MS)) {
            return false;
        }
        return !needFresh || now - walk.start <= FRESH_MS;
    }

    /** Whether two slots are neighbours, wrapping 9↔1 — what one notch of the wheel does. */
    static boolean adjacent(int a, int b, int capacity) {
        int distance = Math.abs(a - b);
        return distance == 1 || (capacity > 2 && distance == capacity - 1);
    }

    // ----- Firing -------------------------------------------------------------

    private void fire(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, byte slot, byte previous) {
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (!ref.isValid() || !shell.altShortcutsActive()) {
                return;
            }
            Store<EntityStore> live = ref.getStore();
            if (shell.altDigitYielded(previous + 1)) {
                // Going back onto another mod's digit with Alt down would be its chord.
                restores.put(player.getUuid(), new Restore(slot, previous));
            } else {
                restore(ref, live, player, slot, previous);
            }
            Player entity = live.getComponent(ref, Player.getComponentType());
            if (entity != null && entity.getPageManager().getCustomPage() != null) {
                // A page already has the player's attention; the hotbar is put
                // back, but the shortcut does not pile a second page on top.
                return;
            }
            shell.activateSlot(player, slot + 1);
        });
    }

    /** Puts the player back on {@code to}, if they are still on {@code from}. World thread. */
    private void restore(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, byte from, byte to) {
        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbar != null && hotbar.getActiveSlot() == from) {
            // Mark first: the restore raises the event synchronously.
            echoes.put(player.getUuid(), new Echo(to, System.currentTimeMillis() + ECHO_MS));
            hotbar.setActiveSlot(to, ref, store);
            player.getPacketHandler().writeNoCache(new SetActiveSlot(hotbar.getSectionId(), to));
        }
    }

    /** Runs a held-back restore once Alt has been up for a latch and a margin. */
    private void settle(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, long now) {
        UUID id = player.getUuid();
        Restore pending = restores.get(id);
        if (pending == null) {
            return;
        }
        Walk walk = walks.get(id);
        if (walk != null && (walk.walking || now - walk.end <= LATCH_MS + RESTORE_MARGIN_MS)) {
            return;
        }
        restores.remove(id);
        store.getExternalData().getWorld().execute(() -> {
            if (ref.isValid()) {
                restore(ref, ref.getStore(), player, pending.from(), pending.to());
            }
        });
    }

    // ----- Systems ------------------------------------------------------------

    /** Watches the walk key. One boolean read per player per tick. */
    private final class WalkSystem extends EntityTickingSystem<EntityStore> {

        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            if (!shell.altShortcutsActive()) {
                return;
            }
            PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
            MovementStatesComponent movement = chunk.getComponent(index, MovementStatesComponent.getComponentType());
            if (player == null || movement == null) {
                return;
            }
            long now = System.currentTimeMillis();
            boolean started = observe(player.getUuid(), movement.getMovementStates(), now);
            if (!restores.isEmpty()) {
                settle(chunk.getReferenceTo(index), store, player, now);
            }
            if (!started) {
                return;
            }
            // Walking just started: honour a digit that arrived a moment early.
            Chord chord = chords.remove(player.getUuid());
            if (chord != null && now - chord.at() <= CHORD_MS) {
                fire(chunk.getReferenceTo(index), store, player, chord.slot(), chord.previous());
            }
        }
    }

    /** Watches hotbar slot changes. */
    private final class SlotSystem extends EntityEventSystem<EntityStore, InventorySetActiveSlotEvent> {

        SlotSystem() {
            super(InventorySetActiveSlotEvent.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull InventorySetActiveSlotEvent event) {
            if (!shell.altShortcutsActive()) {
                return;
            }
            PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
            InventoryComponent.Hotbar hotbar = chunk.getComponent(index, InventoryComponent.Hotbar.getComponentType());
            if (player == null || hotbar == null || event.getInventorySectionId() != hotbar.getSectionId()) {
                return;
            }
            UUID id = player.getUuid();
            long now = System.currentTimeMillis();
            byte slot = event.getNewSlot();

            Echo echo = echoes.get(id);
            if (echo != null) {
                if (now <= echo.until() && echo.slot() == slot) {
                    echoes.remove(id);
                    return;
                }
                if (now > echo.until()) {
                    echoes.remove(id);
                }
            }

            int previous = event.getPreviousSlot();
            int capacity = hotbar.getInventory() == null ? SLOTS : hotbar.getInventory().getCapacity();
            if (slot < 0 || slot >= Math.min(capacity, SLOTS) || previous < 0 || previous == slot) {
                return;
            }
            MovementStatesComponent movement = chunk.getComponent(index, MovementStatesComponent.getComponentType());
            observe(id, movement == null ? null : movement.getMovementStates(), now);

            int digit = slot + 1;
            if (shell.altDigitYielded(digit)) {
                // The other mod acts on this press and then puts the player
                // back on `previous`; that move is its echo, not ALT+previous.
                chords.remove(id);
                echoes.put(id, new Echo((byte) previous, now + ECHO_MS));
                return;
            }
            if (!shell.altDigitOurs(digit)) {
                chords.remove(id);
                return;
            }

            boolean neighbour = adjacent(slot, previous, capacity);
            if (altHeld(id, neighbour, now)) {
                chords.remove(id);
                fire(chunk.getReferenceTo(index), store, player, slot, (byte) previous);
            } else if (!neighbour) {
                chords.put(id, new Chord(slot, (byte) previous, now));
            }
        }
    }
}
