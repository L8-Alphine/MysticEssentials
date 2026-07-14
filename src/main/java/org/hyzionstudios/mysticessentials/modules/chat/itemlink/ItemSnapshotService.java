package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemArmor;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTool;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemWeapon;
import com.hypixel.hytale.server.core.asset.type.item.config.damageData.DamageBreakdown;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Captures, stores, and expires immutable {@link ItemSnapshot}s and keeps a
 * per-recipient "recently shared items" history.
 *
 * <p>Capture runs on the sender's world thread (ECS access rule) and reads the
 * held item via {@code Inventory.getItemInHand()}. Only display-safe fields are
 * copied out — the live {@code ItemStack} and its private metadata are dropped
 * on the spot, so the details UI is a pure read-only view and can never produce
 * an inventory-compatible copy.</p>
 */
public final class ItemSnapshotService {

    /** How long the async chat path waits for the sender's world thread to capture. */
    private static final long CAPTURE_TIMEOUT_MS = 500L;

    /** Unambiguous lowercase alphabet (no 0/o/1/l/i) for short, typeable view codes. */
    private static final char[] CODE_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz".toCharArray();
    private static final int CODE_LENGTH = 4;

    private final MysticCore core;
    private volatile ItemLinkConfig config;

    private final Map<String, Entry> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<String>> recentByPlayer = new ConcurrentHashMap<>();

    private record Entry(ItemSnapshot snapshot, long expiresAtMs) {
    }

    public ItemSnapshotService(MysticCore core, ItemLinkConfig config) {
        this.core = core;
        this.config = config;
    }

    public void updateConfig(ItemLinkConfig config) {
        this.config = config;
    }

    // ----- Capture --------------------------------------------------------------

    /**
     * Captures the sender's held item on their world thread, stores it, and
     * returns it. Blocks briefly (bounded by {@link #CAPTURE_TIMEOUT_MS}) because
     * it is called from the async chat pipeline, never the world tick itself.
     *
     * @return the stored snapshot, or empty if the hand is empty or capture failed
     */
    // getItemInHand() is deprecated-for-removal in 0.5.6 but is the correct
    // held-item accessor (it honours the tools section over the hotbar) and works
    // at runtime, like other deprecated-for-removal 0.5.6 APIs this mod relies on.
    @SuppressWarnings("removal")
    public Optional<ItemSnapshot> captureHeld(PlayerRef sender, String channelName) {
        String code = generateCode();
        CompletableFuture<ItemSnapshot> future = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(sender, (store, ref, world) -> {
            try {
                Player entity = store.getComponent(ref, Player.getComponentType());
                ItemStack held = entity == null ? null : entity.getInventory().getItemInHand();
                if (held == null || held.isEmpty()) {
                    future.complete(null);
                    return;
                }
                future.complete(build(code, held, sender, channelName,
                        world == null ? "" : world.getName()));
            } catch (Throwable t) {
                future.complete(null);
            }
        });
        if (!dispatched) {
            return Optional.empty();
        }
        ItemSnapshot snapshot;
        try {
            snapshot = future.get(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (snapshot == null) {
            return Optional.empty();
        }
        store(snapshot);
        return Optional.of(snapshot);
    }

    /** Builds a snapshot from a live stack. MUST run on the owning world thread. */
    private ItemSnapshot build(String code, ItemStack stack, PlayerRef sender, String channelName,
            String worldName) {
        String itemId = stack.getItemId();
        Item item = safeItem(stack);

        String translationKey = safeString(() -> item == null ? null : item.getTranslationKey());
        String descriptionKey = safeString(() -> item == null ? null : item.getDescriptionTranslationKey());
        int qualityIndex = item == null ? 0 : safeInt(item::getQualityIndex, 0);
        int itemLevel = item == null ? 0 : safeInt(item::getItemLevel, 0);
        String subCategory = safeString(() -> item == null ? null : item.getSubCategory());
        String category = firstCategory(item);

        ItemLinkConfig.Rarity rarity = resolveRarityForId(itemId, qualityIndex);

        double durability = safeDouble(stack::getDurability, 0);
        double maxDurability = safeDouble(stack::getMaxDurability, 0);
        boolean unbreakable = safeBool(stack::isUnbreakable, false);

        List<ItemSnapshot.Stat> stats = extractStats(item, itemLevel);
        String customName = extractCustomName(stack);

        return new ItemSnapshot(
                code,
                itemId,
                Math.max(1, safeInt(stack::getQuantity, 1)),
                translationKey,
                customName,
                descriptionKey,
                qualityIndex,
                rarity.name,
                rarity.color,
                itemLevel,
                category,
                subCategory,
                durability,
                maxDurability,
                unbreakable,
                stats,
                sender.getUuid().toString(),
                sender.getUsername(),
                channelName == null ? "" : channelName,
                worldName == null ? "" : worldName,
                System.currentTimeMillis());
    }

    private List<ItemSnapshot.Stat> extractStats(Item item, int itemLevel) {
        List<ItemSnapshot.Stat> stats = new ArrayList<>();
        if (item == null) {
            return stats;
        }
        if (itemLevel > 0) {
            stats.add(new ItemSnapshot.Stat("Item Level", Integer.toString(itemLevel)));
        }
        try {
            ItemWeapon weapon = item.getWeapon();
            if (weapon != null) {
                DamageBreakdown breakdown = weapon.getBasicDamageBreakdown();
                if (breakdown != null && breakdown.entries() != null) {
                    for (DamageBreakdown.Entry entry : breakdown.entries()) {
                        if (entry == null) {
                            continue;
                        }
                        stats.add(new ItemSnapshot.Stat(prettifyKey(entry.labelKey(), "Damage"),
                                formatRange(entry.min(), entry.max())));
                    }
                }
            }
        } catch (Throwable ignored) {
            // Stat extraction is best-effort; a missing accessor must not fail capture.
        }
        try {
            ItemArmor armor = item.getArmor();
            if (armor != null) {
                stats.add(new ItemSnapshot.Stat("Damage Resistance",
                        formatNumber(armor.getBaseDamageResistance())));
            }
        } catch (Throwable ignored) {
        }
        try {
            ItemTool tool = item.getTool();
            if (tool != null) {
                stats.add(new ItemSnapshot.Stat("Speed", formatNumber(tool.getSpeed())));
            }
        } catch (Throwable ignored) {
        }
        return stats;
    }

    /** Best-effort custom display name from metadata; never exposes raw metadata otherwise. */
    private static String extractCustomName(ItemStack stack) {
        try {
            var metadata = stack.getMetadata();
            if (metadata == null || metadata.isEmpty()) {
                return null;
            }
            for (String key : List.of("customName", "displayName", "name", "CustomName", "DisplayName")) {
                if (metadata.containsKey(key) && metadata.get(key).isString()) {
                    String value = metadata.getString(key).getValue();
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Unknown metadata shapes are simply treated as "no custom name".
        }
        return null;
    }

    // ----- Storage & history ----------------------------------------------------

    public void store(ItemSnapshot snapshot) {
        prune();
        long ttl = Math.max(1, config.snapshot.retentionSeconds) * 1000L;
        byId.put(snapshot.id, new Entry(snapshot, System.currentTimeMillis() + ttl));
    }

    /**
     * A short, currently-unique, human-typeable code for {@code /itemview}. The
     * code is only reserved once the snapshot is stored (after capture), so under
     * heavy concurrent sharing a collision could briefly expire an older link
     * early — negligible for chat volume against a ~810k code space.
     */
    private String generateCode() {
        for (int attempt = 0; attempt < 64; attempt++) {
            String code = randomCode(CODE_LENGTH);
            if (!byId.containsKey(code)) {
                return code;
            }
        }
        String code;
        do {
            code = randomCode(CODE_LENGTH + 4);
        } while (byId.containsKey(code));
        return code;
    }

    private static String randomCode(int length) {
        java.util.concurrent.ThreadLocalRandom random =
                java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    public Optional<ItemSnapshot> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        Entry entry = byId.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtMs() < System.currentTimeMillis()) {
            byId.remove(id);
            return Optional.empty();
        }
        return Optional.of(entry.snapshot());
    }

    /** Records a shared item in each recipient's recent-links history. */
    public void recordHistory(ItemSnapshot snapshot, Collection<PlayerRef> recipients) {
        if (!config.history.enabled || snapshot == null || recipients == null) {
            return;
        }
        int max = Math.max(1, config.history.maximumEntries);
        for (PlayerRef recipient : recipients) {
            if (recipient == null) {
                continue;
            }
            Deque<String> deque = recentByPlayer.computeIfAbsent(recipient.getUuid(),
                    uuid -> new ArrayDeque<>());
            synchronized (deque) {
                deque.remove(snapshot.id);
                deque.addFirst(snapshot.id);
                while (deque.size() > max) {
                    deque.removeLast();
                }
            }
        }
    }

    /** Resolved, unexpired recent snapshots for a player, newest first. */
    public List<ItemSnapshot> recent(UUID player) {
        Deque<String> deque = recentByPlayer.get(player);
        if (deque == null) {
            return List.of();
        }
        List<ItemSnapshot> out = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        synchronized (deque) {
            for (String id : deque) {
                Optional<ItemSnapshot> snapshot = get(id);
                if (snapshot.isPresent()) {
                    out.add(snapshot.get());
                } else {
                    stale.add(id);
                }
            }
            deque.removeAll(stale);
        }
        return out;
    }

    public Optional<ItemSnapshot> latest(UUID player) {
        List<ItemSnapshot> recent = recent(player);
        return recent.isEmpty() ? Optional.empty() : Optional.of(recent.get(0));
    }

    /** The nth recent snapshot (1-based) for a player. */
    public Optional<ItemSnapshot> recentAt(UUID player, int oneBasedIndex) {
        List<ItemSnapshot> recent = recent(player);
        int idx = oneBasedIndex - 1;
        return idx >= 0 && idx < recent.size() ? Optional.of(recent.get(idx)) : Optional.empty();
    }

    public void forget(UUID player) {
        recentByPlayer.remove(player);
    }

    private void prune() {
        long now = System.currentTimeMillis();
        byId.entrySet().removeIf(e -> e.getValue().expiresAtMs() < now);
        int max = Math.max(1, config.snapshot.maximumSnapshots);
        if (byId.size() > max) {
            // Oldest-expiry-first eviction to bound memory under a burst.
            byId.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(
                            (a, b) -> Long.compare(a.expiresAtMs(), b.expiresAtMs())))
                    .limit(byId.size() - max)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(byId::remove);
        }
    }

    // ----- Rarity ---------------------------------------------------------------

    /** Rarity by item-id rule (custom RPG items), falling back to the quality-index map. */
    ItemLinkConfig.Rarity resolveRarityForId(String itemId, int qualityIndex) {
        if (config.rarityRules != null && itemId != null) {
            String lower = itemId.toLowerCase(Locale.ROOT);
            for (ItemLinkConfig.RarityRule rule : config.rarityRules) {
                if (rule == null || rule.match == null || rule.match.isBlank()) {
                    continue;
                }
                boolean hit;
                try {
                    hit = rule.regex
                            ? lower.matches(rule.match.toLowerCase(Locale.ROOT))
                            : lower.contains(rule.match.toLowerCase(Locale.ROOT));
                } catch (RuntimeException badPattern) {
                    hit = false;
                }
                if (hit) {
                    return new ItemLinkConfig.Rarity(qualityIndex,
                            rule.rarity == null ? "Common" : rule.rarity,
                            rule.color == null ? "#FFFFFF" : rule.color);
                }
            }
        }
        return resolveRarity(qualityIndex);
    }

    ItemLinkConfig.Rarity resolveRarity(int qualityIndex) {
        if (config.rarities != null) {
            for (ItemLinkConfig.Rarity rarity : config.rarities) {
                if (rarity != null && rarity.index == qualityIndex) {
                    return rarity;
                }
            }
        }
        return new ItemLinkConfig.Rarity(qualityIndex, "Common", "#FFFFFF");
    }

    // ----- Helpers --------------------------------------------------------------

    private static Item safeItem(ItemStack stack) {
        try {
            return stack.getItem();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstCategory(Item item) {
        try {
            String[] categories = item == null ? null : item.getCategories();
            if (categories != null && categories.length > 0) {
                return prettifyKey(categories[0], null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String prettifyKey(String key, String fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        String base = key;
        int dot = base.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < base.length()) {
            base = base.substring(dot + 1);
        }
        return ItemSnapshot.prettifyId(base);
    }

    private static String formatRange(float min, float max) {
        if (Math.abs(min - max) < 0.001f) {
            return formatNumber(min);
        }
        return formatNumber(min) + "-" + formatNumber(max);
    }

    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private interface StringSupplier {
        String get() throws Throwable;
    }

    private static String safeString(StringSupplier supplier) {
        try {
            String value = supplier.get();
            return value == null || value.isBlank() ? null : value;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int safeInt(IntSupplier supplier, int fallback) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static double safeDouble(DoubleSupplier supplier, double fallback) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean safeBool(BoolSupplier supplier, boolean fallback) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private interface IntSupplier {
        int get() throws Throwable;
    }

    private interface DoubleSupplier {
        double get() throws Throwable;
    }

    private interface BoolSupplier {
        boolean get() throws Throwable;
    }

    void logDebug(String message) {
        core.log(Level.FINE, "[chat] item-links: " + message);
    }
}
