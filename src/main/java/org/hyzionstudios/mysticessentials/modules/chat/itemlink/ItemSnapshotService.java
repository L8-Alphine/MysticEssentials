package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.hyzionstudios.mysticessentials.api.item.ItemInspectionContext;
import org.hyzionstudios.mysticessentials.api.item.ItemInspectionService;
import org.hyzionstudios.mysticessentials.api.item.ItemViewData;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.item.ItemViewConfig;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Captures, stores, signs, and expires {@link ItemSnapshot}s, and keeps each
 * player's recently-seen-links history.
 *
 * <p>Capture runs on the sender's world thread (the ECS access rule) and reads
 * the real held stack through the shared {@link ItemInspectionService}. What is
 * stored is the resulting normalized view — plain data with no live stack
 * behind it — which is what makes a shared item both tamper-proof and impossible
 * to duplicate through inspection.</p>
 *
 * <p>Three limits bound what a player can do with this: snapshots expire, each
 * player may hold only so many live snapshots at once, and an oversized view is
 * trimmed before storage. Together they stop item-link spam from becoming a
 * memory-exhaustion vector.</p>
 */
public final class ItemSnapshotService {

    /** How long the async chat path waits for the sender's world thread to capture. */
    private static final long CAPTURE_TIMEOUT_MS = 500L;

    /** Unambiguous lowercase alphabet (no 0/o/1/l/i) for short, typeable view codes. */
    private static final char[] CODE_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz".toCharArray();
    private static final int CODE_LENGTH = 4;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final MysticCore core;
    private final ItemInspectionService inspection;
    private volatile ItemViewConfig config;

    /** Per-boot signing key. Never leaves the server, never reaches a client. */
    private final byte[] signingKey = newSigningKey();

    private final Map<String, ItemSnapshot> byId = new ConcurrentHashMap<>();
    /** Live snapshot ids per sharing player, oldest first, for the per-player cap. */
    private final Map<UUID, Deque<String>> bySender = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<String>> recentByPlayer = new ConcurrentHashMap<>();

    public ItemSnapshotService(MysticCore core, ItemInspectionService inspection,
            ItemViewConfig config) {
        this.core = core;
        this.inspection = inspection;
        this.config = config;
    }

    public void updateConfig(ItemViewConfig config) {
        this.config = config;
    }

    // ----- Capture --------------------------------------------------------------

    /**
     * Captures the sender's held item on their world thread, stores it, and
     * returns it. Blocks briefly (bounded by {@link #CAPTURE_TIMEOUT_MS}) because
     * it is called from the async chat pipeline, never the world tick itself.
     *
     * @return the stored snapshot, or empty when the hand is empty or capture failed
     */
    // getItemInHand() is deprecated-for-removal in 0.5.6 but is the correct
    // held-item accessor (it honours the tools section over the hotbar) and works
    // at runtime, like other deprecated-for-removal 0.5.6 APIs this mod relies on.
    @SuppressWarnings("removal")
    public Optional<ItemSnapshot> captureHeld(PlayerRef sender, String channelName) {
        CompletableFuture<ItemSnapshot> future = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(sender, (store, ref, world) -> {
            try {
                Player entity = store.getComponent(ref, Player.getComponentType());
                ItemStack held = entity == null ? null : entity.getInventory().getItemInHand();
                if (held == null || held.isEmpty()) {
                    future.complete(null);
                    return;
                }
                future.complete(build(held, sender, channelName,
                        world == null ? "" : world.getName()));
            } catch (Throwable t) {
                core.log(Level.FINE, "[item-links] Capture failed for " + sender.getUsername()
                        + ": " + t);
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

    /** Builds a signed snapshot from a live stack. MUST run on the owning world thread. */
    private ItemSnapshot build(ItemStack stack, PlayerRef sender, String channelName,
            String worldName) {
        ItemInspectionContext context = ItemInspectionContext
                .builder(ItemInspectionContext.Purpose.CHAT_SHARE)
                .owner(sender.getUuid(), sender.getUsername())
                .channelId(channelName)
                .worldName(worldName)
                .build();

        ItemViewData view = trimToBudget(inspection.inspect(stack, context));

        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(Math.max(1, config.snapshots.expirationMinutes) * 60L);
        String code = generateCode();
        String serverId = safeServerId();
        String signature = sign(code, view.itemId(), sender.getUuid(), now, serverId);

        return new ItemSnapshot(code, view, sender.getUuid(), sender.getUsername(),
                channelName, worldName, serverId, now, expiry, signature);
    }

    /**
     * Drops the technical-metadata tail of an oversized view.
     *
     * <p>The budget exists so a single item carrying a huge metadata document
     * cannot be turned into a server-wide memory cost by sharing it repeatedly.
     * Identity, classification, statistics, and modifiers are never trimmed —
     * only the raw diagnostic dump, which is the part that can grow without
     * bound and the part a player is least likely to miss.</p>
     */
    private ItemViewData trimToBudget(ItemViewData view) {
        int budget = Math.max(1024, config.snapshots.maximumMetadataBytes);
        int size = estimateBytes(view);
        if (size <= budget || view.technicalMetadata().isEmpty()) {
            return view;
        }
        core.log(Level.FINE, "[item-links] Trimming oversized metadata for '" + view.itemId()
                + "' (" + size + " > " + budget + " bytes).");
        return view.toBuilder()
                .clearTechnical()
                .addTechnical("Metadata", "omitted — exceeded the " + budget + " byte budget")
                .build();
    }

    /** A cheap upper-bound estimate of a view's retained text size. */
    private static int estimateBytes(ItemViewData view) {
        int total = view.itemId().length() + view.plainName().length();
        for (var entry : view.technicalMetadata()) {
            total += entry.key().length() + entry.value().length() + 8;
        }
        for (var line : view.lore()) {
            total += line.markup().length();
        }
        for (var line : view.description()) {
            total += line.markup().length();
        }
        return total * 2;
    }

    // ----- Storage & history ----------------------------------------------------

    public void store(ItemSnapshot snapshot) {
        prune();
        byId.put(snapshot.id, snapshot);
        enforcePerSenderCap(snapshot);
    }

    /**
     * Keeps a single player from pinning unbounded memory: once they hold the
     * configured number of live snapshots, sharing another expires their oldest.
     */
    private void enforcePerSenderCap(ItemSnapshot snapshot) {
        if (snapshot.senderId == null) {
            return;
        }
        int max = Math.max(1, config.snapshots.maxPerPlayer);
        Deque<String> owned = bySender.computeIfAbsent(snapshot.senderId, uuid -> new ArrayDeque<>());
        synchronized (owned) {
            owned.addLast(snapshot.id);
            while (owned.size() > max) {
                byId.remove(owned.removeFirst());
            }
        }
    }

    /**
     * A short, currently-unique, human-typeable code. Codes are drawn from a
     * ~810k space and reserved on store; a collision under heavy concurrent
     * sharing could expire an older link early, which is harmless.
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
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** Looks up a snapshot by id, treating an expired one as absent. */
    public Optional<ItemSnapshot> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        ItemSnapshot snapshot = byId.get(id);
        if (snapshot == null) {
            return Optional.empty();
        }
        if (snapshot.isExpired()) {
            byId.remove(id);
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    /**
     * Verifies a snapshot that did not come from this server's own map — the
     * cross-server relay path. A snapshot whose signature does not verify is
     * discarded rather than shown, so a compromised peer cannot inject fabricated
     * item data into this server's chat.
     */
    public boolean verify(ItemSnapshot snapshot) {
        if (snapshot == null || snapshot.signature == null) {
            return false;
        }
        String expected = sign(snapshot.id, snapshot.view.itemId(), snapshot.senderId,
                snapshot.createdAt, snapshot.serverId);
        return constantTimeEquals(expected, snapshot.signature);
    }

    /** Records a shared item in each recipient's recent-links history. */
    public void recordHistory(ItemSnapshot snapshot, Collection<PlayerRef> recipients) {
        if (snapshot == null || recipients == null) {
            return;
        }
        int max = Math.max(1, config.snapshots.historyEntriesPerPlayer);
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
        int index = oneBasedIndex - 1;
        return index >= 0 && index < recent.size() ? Optional.of(recent.get(index)) : Optional.empty();
    }

    public void forget(UUID player) {
        recentByPlayer.remove(player);
        bySender.remove(player);
    }

    private void prune() {
        Instant now = Instant.now();
        byId.values().removeIf(snapshot -> snapshot.expiresAt != null
                && now.isAfter(snapshot.expiresAt));
        int max = Math.max(1, config.snapshots.maximumSnapshots);
        if (byId.size() > max) {
            // Oldest-expiry-first eviction to bound memory under a burst.
            byId.entrySet().stream()
                    .sorted((a, b) -> a.getValue().expiresAt.compareTo(b.getValue().expiresAt))
                    .limit(byId.size() - max)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(byId::remove);
        }
    }

    // ----- Signing ---------------------------------------------------------------

    private static byte[] newSigningKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /**
     * Fields are joined with the ASCII unit separator rather than a space,
     * because an item id is arbitrary text: with a printable delimiter, two
     * different snapshots could serialize to the same payload and share a
     * signature. U+001F cannot occur in any of these fields.
     */
    private static final String FIELD_SEPARATOR = String.valueOf((char) 31);

    private String sign(String id, String itemId, UUID senderId, Instant createdAt, String serverId) {
        String payload = String.join(FIELD_SEPARATOR,
                id == null ? "" : id,
                itemId == null ? "" : itemId,
                senderId == null ? "" : senderId.toString(),
                createdAt == null ? "0" : Long.toString(createdAt.toEpochMilli()),
                serverId == null ? "" : serverId);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // Without a MAC the local path is still safe (snapshots never leave the
            // server unsigned-but-trusted); only cross-server verification degrades.
            core.log(Level.WARNING, "[item-links] Snapshot signing unavailable: " + e);
            return "";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length() || a.isEmpty()) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < a.length(); i++) {
            difference |= a.charAt(i) ^ b.charAt(i);
        }
        return difference == 0;
    }

    private String safeServerId() {
        try {
            String id = core.redis().serverId();
            return id == null ? "" : id;
        } catch (Throwable t) {
            return "";
        }
    }
}
