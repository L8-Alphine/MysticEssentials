package org.hyzionstudios.mysticessentials.modules.lootbox.hytale;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bson.BsonDocument;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.KeyMarker;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The player's key-holding containers (hotbar, storage, backpack; the same set
 * Mail draws from). Every method MUST run on the player's world thread.
 */
public final class InventoryAdapter {

    /** Which container a stack sits in, so a later hop can find the same slot again. */
    public enum Section {
        HOTBAR,
        STORAGE,
        BACKPACK
    }

    /** A valid-looking key stack: its marker verified against the server secret. */
    public record KeyStack(Section section, short slot, KeyMarker marker, int quantity) {
    }

    private InventoryAdapter() {
    }

    /** Key stacks for {@code keyIds} whose marker verifies against {@code secret}. */
    public static List<KeyStack> findKeys(Store<EntityStore> store, Ref<EntityStore> entity, Set<String> keyIds,
            byte[] secret) {
        List<KeyStack> found = new ArrayList<>();
        for (Section section : Section.values()) {
            ItemContainer container = container(store, entity, section);
            if (container == null) {
                continue;
            }
            for (short slot = 0; slot < container.getCapacity(); slot++) {
                KeyMarker marker = KeyItemFactory.read(container.getItemStack(slot));
                if (marker != null && keyIds.contains(marker.keyId()) && marker.verify(secret)) {
                    found.add(new KeyStack(section, slot, marker, container.getItemStack(slot).getQuantity()));
                }
            }
        }
        return found;
    }

    /** Every stack carrying a marker with this serial (after a dupe there may be several). */
    public static List<KeyStack> findSerial(Store<EntityStore> store, Ref<EntityStore> entity, String serial) {
        List<KeyStack> found = new ArrayList<>();
        for (Section section : Section.values()) {
            ItemContainer container = container(store, entity, section);
            if (container == null) {
                continue;
            }
            for (short slot = 0; slot < container.getCapacity(); slot++) {
                KeyMarker marker = KeyItemFactory.read(container.getItemStack(slot));
                if (marker != null && serial.equals(marker.serial())) {
                    found.add(new KeyStack(section, slot, marker, container.getItemStack(slot).getQuantity()));
                }
            }
        }
        return found;
    }

    /**
     * Removes one key with {@code serial}, preferring {@code expected}'s slot and
     * searching again if the player moved it.
     *
     * @return {@code false} when no stack with that serial is left
     */
    public static boolean removeOne(Store<EntityStore> store, Ref<EntityStore> entity, KeyStack expected) {
        if (takeOne(store, entity, expected.section(), expected.slot(), expected.marker().serial())) {
            return true;
        }
        for (KeyStack stack : findSerial(store, entity, expected.marker().serial())) {
            if (takeOne(store, entity, stack.section(), stack.slot(), stack.marker().serial())) {
                return true;
            }
        }
        return false;
    }

    /** Items carrying {@code serial} across the key-holding containers: a batch's count (P2-K), or a serial's copies. */
    public static int countSerial(Store<EntityStore> store, Ref<EntityStore> entity, String serial) {
        int count = 0;
        for (KeyStack stack : findSerial(store, entity, serial)) {
            count += stack.quantity();
        }
        return count;
    }

    /**
     * Removes exactly {@code count} items from {@code expected}'s slot, for a
     * deposit of a held stack (P2-K). Nothing is removed unless the slot still
     * holds at least {@code count} items with that marker's serial.
     */
    public static boolean takeCount(Store<EntityStore> store, Ref<EntityStore> entity, KeyStack expected, int count) {
        ItemContainer container = container(store, entity, expected.section());
        short slot = expected.slot();
        if (container == null || slot < 0 || slot >= container.getCapacity() || count < 1) {
            return false;
        }
        ItemStack stack = container.getItemStack(slot);
        KeyMarker marker = KeyItemFactory.read(stack);
        if (marker == null || !expected.marker().serial().equals(marker.serial()) || stack.getQuantity() < count) {
            return false;
        }
        int left = stack.getQuantity() - count;
        container.setItemStackForSlot(slot, left <= 0 ? ItemStack.EMPTY : stack.withQuantity(left));
        return true;
    }

    /** Removes every stack carrying {@code serial} (a spent key that came back). @return stacks removed. */
    public static int removeAll(Store<EntityStore> store, Ref<EntityStore> entity, String serial) {
        int removed = 0;
        for (KeyStack stack : findSerial(store, entity, serial)) {
            ItemContainer container = container(store, entity, stack.section());
            if (container != null) {
                container.setItemStackForSlot(stack.slot(), ItemStack.EMPTY);
                removed++;
            }
        }
        return removed;
    }

    /** Gives {@code stack}. @return what did not fit, or {@code null} when everything fit. */
    public static ItemStack give(Store<EntityStore> store, Ref<EntityStore> entity, ItemStack stack) {
        ItemStack remainder = Player.giveItem(stack, entity, store).getRemainder();
        return ItemStack.isEmpty(remainder) ? null : remainder;
    }

    /**
     * The id of the item in the player's hand, for the editors' "Use held item".
     * {@code getItemInHand} prefers an active builder tool over the hotbar slot,
     * which is what the player sees in their hand.
     *
     * @return the item id, or {@code null} when the hand is empty
     */
    public static String heldItemId(Store<EntityStore> store, Ref<EntityStore> entity) {
        ItemStack held = InventoryComponent.getItemInHand(store, entity);
        return ItemStack.isEmpty(held) ? null : held.getItemId();
    }

    /**
     * The stack in the active hotbar slot when it carries a key marker, for
     * {@code /lootbox key deposit}. Its signature is NOT checked here. A builder
     * tool in hand does not count: keys are never tools.
     *
     * @return the stack and its slot, or {@code null} for an empty hand or an ordinary item
     */
    public static KeyStack heldKey(Store<EntityStore> store, Ref<EntityStore> entity) {
        InventoryComponent.Hotbar hotbar = store.getComponent(entity, InventoryComponent.Hotbar.getComponentType());
        if (hotbar == null) {
            return null;
        }
        short slot = hotbar.getActiveSlot();
        ItemContainer container = hotbar.getInventory();
        if (slot < 0 || slot >= container.getCapacity()) {
            return null;
        }
        ItemStack stack = container.getItemStack(slot);
        KeyMarker marker = KeyItemFactory.read(stack);
        return marker == null ? null : new KeyStack(Section.HOTBAR, slot, marker, stack.getQuantity());
    }

    /** Builds a stack from stored parts; {@code metadataJson} may be null. */
    public static ItemStack stack(String itemId, int quantity, String metadataJson) {
        BsonDocument metadata = metadataJson == null || metadataJson.isBlank() ? null : BsonDocument.parse(metadataJson);
        return new ItemStack(itemId, Math.max(1, quantity), metadata);
    }

    private static boolean takeOne(Store<EntityStore> store, Ref<EntityStore> entity, Section section, short slot,
            String serial) {
        ItemContainer container = container(store, entity, section);
        if (container == null || slot < 0 || slot >= container.getCapacity()) {
            return false;
        }
        ItemStack stack = container.getItemStack(slot);
        KeyMarker marker = KeyItemFactory.read(stack);
        if (marker == null || !serial.equals(marker.serial())) {
            return false;
        }
        int left = stack.getQuantity() - 1;
        container.setItemStackForSlot(slot, left <= 0 ? ItemStack.EMPTY : stack.withQuantity(left));
        return true;
    }

    private static ItemContainer container(Store<EntityStore> store, Ref<EntityStore> entity, Section section) {
        ComponentType<EntityStore, ? extends InventoryComponent> type = switch (section) {
            case HOTBAR -> InventoryComponent.Hotbar.getComponentType();
            case STORAGE -> InventoryComponent.Storage.getComponentType();
            case BACKPACK -> InventoryComponent.Backpack.getComponentType();
        };
        InventoryComponent component = store.getComponent(entity, type);
        return component == null ? null : component.getInventory();
    }
}
