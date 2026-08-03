package org.hyzionstudios.mysticessentials.core.item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.item.ItemInspectionContext;
import org.hyzionstudios.mysticessentials.api.item.ItemInspectionService;
import org.hyzionstudios.mysticessentials.api.item.ItemNames;
import org.hyzionstudios.mysticessentials.api.item.ItemViewBuilder;
import org.hyzionstudios.mysticessentials.api.item.ItemViewData;
import org.hyzionstudios.mysticessentials.api.item.ItemViewProvider;
import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * The concrete {@link ItemInspectionService}: runs the generic native reader,
 * then every applicable provider in ascending priority, and returns the merged
 * view.
 *
 * <p>Two properties matter more than anything else here.</p>
 *
 * <p><b>Inspection cannot fail.</b> A provider that throws is logged (rate-limited
 * per provider so a broken mod cannot flood the log) and skipped; the remaining
 * providers and everything the native reader already gathered still make it into
 * the result. Even a {@code null} or empty stack produces a placeholder view, so
 * the caller never has to handle "no data" and a player never sees a blank
 * panel.</p>
 *
 * <p><b>Absence is preserved.</b> Nothing in this class substitutes a default for
 * a missing classification, and nothing inspects a display name to decide whether
 * a value is "really" there. A quality named {@code Null} arrives at the renderer
 * exactly as the item declared it.</p>
 */
public final class ItemInspectionServiceImpl implements ItemInspectionService {

    private final MysticCore core;
    private volatile ItemViewConfig config;
    private volatile NativeItemInspector nativeInspector;

    private final Map<String, ItemViewProvider> providers = new ConcurrentHashMap<>();
    /** Last log timestamp per provider id, for the error-log cooldown. */
    private final Map<String, Long> lastErrorLogMs = new ConcurrentHashMap<>();

    public ItemInspectionServiceImpl(MysticCore core, ItemViewConfig config) {
        this.core = core;
        updateConfig(config);
    }

    public void updateConfig(ItemViewConfig config) {
        this.config = config == null ? new ItemViewConfig().normalized() : config;
        this.nativeInspector = new NativeItemInspector(this.config);
    }

    public ItemViewConfig config() {
        return config;
    }

    // ----- Inspection -----------------------------------------------------------

    @Override
    public ItemViewData inspect(ItemStack item, ItemInspectionContext context) {
        ItemInspectionContext effective = context == null ? ItemInspectionContext.api() : context;
        if (item == null || safeIsEmpty(item)) {
            return placeholder();
        }

        String itemId = safeItemId(item);
        ItemViewBuilder builder = ItemViewData.builder(itemId);

        // Stage 1-3: the engine's own view of the item — definition, stack state,
        // structured metadata, tags, plus the configured classification rules.
        try {
            nativeInspector.inspect(item, builder);
        } catch (Throwable t) {
            core.log(Level.WARNING, "[item-view] Native inspection failed for '" + itemId
                    + "'; falling back to identity only: " + t);
        }

        // Stage 4: providers, lowest priority first so the highest-priority one
        // has the last word on any field it chooses to set.
        for (ItemViewProvider provider : providers()) {
            runProvider(provider, item, effective, builder);
        }

        try {
            return builder.build();
        } catch (Throwable t) {
            core.log(Level.WARNING, "[item-view] Could not assemble the view for '" + itemId
                    + "': " + t);
            return ItemViewData.builder(itemId)
                    .displayName(ItemNames.prettify(itemId))
                    .build();
        }
    }

    /**
     * Runs one provider inside a containment boundary. The whole point of this
     * method is that a third-party failure costs that provider's contribution and
     * nothing else — the player still gets an ItemView with the icon, name, id,
     * namespace, and every field the other stages resolved.
     */
    private void runProvider(ItemViewProvider provider, ItemStack item,
            ItemInspectionContext context, ItemViewBuilder builder) {
        String providerId = safeProviderId(provider);
        try {
            if (!provider.supports(item, context)) {
                return;
            }
            provider.populate(item, context, builder);
        } catch (Throwable t) {
            if (!config.providers.catchProviderErrors) {
                throw t instanceof RuntimeException runtime ? runtime : new RuntimeException(t);
            }
            logProviderError(providerId, safeItemId(item), t);
        }
    }

    private void logProviderError(String providerId, String itemId, Throwable error) {
        if (!config.providers.logProviderErrors) {
            return;
        }
        long cooldownMs = Math.max(0, config.providers.errorLogCooldownSeconds) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastErrorLogMs.get(providerId);
        if (last != null && now - last < cooldownMs) {
            return;
        }
        lastErrorLogMs.put(providerId, now);
        core.log(Level.SEVERE, "[item-view] Provider '" + providerId + "' failed for item '"
                + itemId + "'; continuing without its data: " + error);
    }

    /** The view shown when there is genuinely no item — never a blank screen. */
    private static ItemViewData placeholder() {
        return ItemViewData.builder("")
                .displayName("Unknown Item")
                .addTechnical("state", "no item")
                .build();
    }

    // ----- Provider registry ------------------------------------------------------

    @Override
    public void registerProvider(ItemViewProvider provider) {
        if (provider == null) {
            return;
        }
        String id = safeProviderId(provider);
        ItemViewProvider previous = providers.put(id, provider);
        lastErrorLogMs.remove(id);
        core.log(Level.INFO, "[item-view] " + (previous == null ? "Registered" : "Replaced")
                + " ItemView provider '" + id + "' (priority " + safePriority(provider) + ").");
    }

    @Override
    public boolean unregisterProvider(String providerId) {
        if (providerId == null) {
            return false;
        }
        lastErrorLogMs.remove(providerId);
        return providers.remove(providerId) != null;
    }

    @Override
    public List<ItemViewProvider> providers() {
        List<ItemViewProvider> ordered = new ArrayList<>(providers.values());
        ordered.sort(Comparator.comparingInt(ItemInspectionServiceImpl::safePriority)
                .thenComparing(ItemInspectionServiceImpl::safeProviderId));
        return ordered;
    }

    // ----- Defensive accessors ----------------------------------------------------

    private static String safeProviderId(ItemViewProvider provider) {
        try {
            String id = provider.getProviderId();
            return id == null || id.isBlank() ? provider.getClass().getName() : id;
        } catch (Throwable t) {
            return provider.getClass().getName();
        }
    }

    private static int safePriority(ItemViewProvider provider) {
        try {
            return provider.getPriority();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String safeItemId(ItemStack stack) {
        try {
            String id = stack.getItemId();
            return id == null ? "" : id;
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean safeIsEmpty(ItemStack stack) {
        try {
            return stack.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
