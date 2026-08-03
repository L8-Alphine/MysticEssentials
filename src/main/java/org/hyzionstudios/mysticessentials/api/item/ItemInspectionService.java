package org.hyzionstudios.mysticessentials.api.item;

import java.util.List;

import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Converts a live item into a normalized {@link ItemViewData}, merging the
 * engine's own item definition with whatever registered
 * {@link ItemViewProvider}s contribute.
 *
 * <p>Resolution proceeds in a fixed order, each stage layering over the last:</p>
 * <ol>
 *   <li>generic inspection of the native item definition and stack state,</li>
 *   <li>structured item metadata and tags,</li>
 *   <li>configuration overrides ({@code modules/chat/item-view.json}),</li>
 *   <li>registered providers, in ascending priority.</li>
 * </ol>
 *
 * <p>Inspection always succeeds. An unknown item, an item whose data this mod
 * does not understand, or a provider that throws all still yield a view with at
 * least an icon, a name, an id, and a namespace — because no valid item may open
 * to a blank screen.</p>
 *
 * <p>Implementations read live ECS state and must therefore be called on the
 * world thread that owns the item.</p>
 */
public interface ItemInspectionService {

    /**
     * Inspects {@code item} and returns its normalized view.
     *
     * @param item    the stack to inspect; an empty or {@code null} stack yields a
     *                minimal placeholder view rather than an exception
     * @param context why the inspection is happening
     */
    ItemViewData inspect(ItemStack item, ItemInspectionContext context);

    /**
     * Registers a provider. Re-registering the same {@link ItemViewProvider#getProviderId()}
     * replaces the previous registration, so a mod reload does not accumulate
     * duplicates.
     */
    void registerProvider(ItemViewProvider provider);

    /** Removes a provider by id. @return whether one was registered. */
    boolean unregisterProvider(String providerId);

    /** Registered providers, in the order they will run (ascending priority). */
    List<ItemViewProvider> providers();
}
