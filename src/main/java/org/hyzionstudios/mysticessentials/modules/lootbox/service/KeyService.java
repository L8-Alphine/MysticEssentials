package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.GrantSource;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.KeyGrantedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.KeyRevokedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.InventoryAdapter;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.KeyItemFactory;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PendingInbox;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.SecureDraw;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;
import org.hyzionstudios.mysticessentials.platform.ItemStackMetadata;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Key definitions in use, physical key issue and revoke, virtual balances, and
 * the signing secret (§4.5). Every physical key gets its ledger entry before the
 * item exists, so there is never an item without a serial to check it against.
 */
public final class KeyService {

    /** Rotation id of the active secret; markers carry it. */
    static final int SECRET_ID = 1;

    /** Result of a grant. {@code pending} counts keys that went to the inbox. */
    public record GrantResult(boolean ok, int delivered, int pending, String problem) {

        static GrantResult failed(String problem) {
            return new GrantResult(false, 0, 0, problem);
        }
    }

    private final MysticCore core;
    private final LootBoxStorage storage;
    private final LootBoxRegistry registry;
    private final AuditService audit;
    private final PlayerLanes lanes;
    private final SecureDraw draw;
    private volatile LootBoxConfig config;
    private byte[] secret;

    public KeyService(MysticCore core, LootBoxStorage storage, LootBoxRegistry registry, AuditService audit,
            PlayerLanes lanes, SecureDraw draw, LootBoxConfig config) {
        this.core = core;
        this.storage = storage;
        this.registry = registry;
        this.audit = audit;
        this.lanes = lanes;
        this.draw = draw;
        this.config = config;
    }

    public void updateConfig(LootBoxConfig config) {
        this.config = config;
    }

    /** Loads the signing secret, creating it on first enable. Kept out of config.json. */
    public void loadSecret() throws IOException {
        Path file = core.paths().moduleDataDir("lootbox").resolve("secret.key");
        if (Files.isRegularFile(file)) {
            secret = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).trim());
            return;
        }
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Base64.getEncoder().encodeToString(fresh), StandardCharsets.US_ASCII);
        secret = fresh;
        core.log(Level.INFO, "[lootbox] generated a new key-signing secret at " + file);
    }

    public byte[] secret() {
        return secret;
    }

    /** {@code null} when allowed, otherwise why the policy profile refuses this source. */
    public String policyProblem(GrantSource source) {
        String profile = config.policyProfile == null ? "" : config.policyProfile.toUpperCase(Locale.ROOT);
        if (source == GrantSource.PAID && !("PAID_RANDOM_RESTRICTED".equals(profile) && config.allowPaidRandomProfile)) {
            return "paid key grants are not allowed under the " + profile + " policy";
        }
        return null;
    }

    // ----- Issue --------------------------------------------------------------

    /**
     * Issues {@code count} keys of {@code keyId}. Physical keys go to the player's
     * inventory when online (the inbox takes what does not fit), and follow
     * {@code keys.offlineGrant} when offline.
     */
    public CompletableFuture<GrantResult> grant(UUID owner, String keyId, int count, boolean virtual,
            GrantSource source, String actor) {
        KeyDraft.Published definition = registry.key(keyId).orElse(null);
        if (definition == null) {
            return CompletableFuture.completedFuture(GrantResult.failed("key '" + keyId + "' is not published"));
        }
        String policy = policyProblem(source);
        if (policy != null) {
            audit.record(actor, "KEY_GRANT_REFUSED", owner.toString(), keyId + " x" + count + " source " + source);
            return CompletableFuture.completedFuture(GrantResult.failed(policy));
        }
        int amount = Math.max(1, Math.min(count, Math.max(1, config.keys.maxGrantPerCommand)));
        PlayerRef online = core.platform().findPlayer(owner).orElse(null);
        boolean asVirtual = virtual || (online == null && "VIRTUAL_CREDIT".equalsIgnoreCase(config.keys.offlineGrant));
        CompletableFuture<GrantResult> result = lanes.run(owner, () -> asVirtual
                ? grantVirtual(owner, keyId, amount)
                : grantPhysical(owner, online, definition, amount, source, actor));
        return result.thenApply(granted -> {
            if (granted.ok()) {
                audit.record(actor, "KEY_GRANT", owner.toString(), keyId + " x" + amount + (asVirtual ? " virtual" : "")
                        + " source " + source + (granted.pending() > 0 ? " pending " + granted.pending() : ""));
                core.getEventBus().publish(new KeyGrantedEvent(owner, keyId, amount, asVirtual, source.name(), actor));
            }
            return granted;
        });
    }

    private CompletableFuture<GrantResult> grantVirtual(UUID owner, String keyId, int amount) {
        return storage.updatePlayer(owner, state -> {
            state.virtualKeys.merge(keyId, amount, Integer::sum);
            return state;
        }).thenApply(state -> new GrantResult(true, amount, 0, null));
    }

    private CompletableFuture<GrantResult> grantPhysical(UUID owner, PlayerRef online, KeyDraft.Published definition,
            int amount, GrantSource source, String actor) {
        List<CompletableFuture<ItemStack>> issued = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            issued.add(issueSerial(owner, definition, source, actor));
        }
        return CompletableFuture.allOf(issued.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
            List<ItemStack> stacks = issued.stream().map(CompletableFuture::join).toList();
            if (online == null) {
                return toInbox(owner, stacks).thenApply(n -> new GrantResult(true, 0, n, null));
            }
            CompletableFuture<List<ItemStack>> leftovers = new CompletableFuture<>();
            boolean dispatched = core.platform().runOnEntityThread(online, (store, entity, world) -> {
                List<ItemStack> left = new ArrayList<>();
                for (ItemStack stack : stacks) {
                    ItemStack remainder = InventoryAdapter.give(store, entity, stack);
                    if (remainder != null) {
                        left.add(remainder);
                    }
                }
                leftovers.complete(left);
            });
            if (!dispatched) {
                leftovers.complete(stacks);
            }
            return leftovers.thenCompose(left -> toInbox(owner, left)
                    .thenApply(n -> new GrantResult(true, stacks.size() - n, n, null)));
        });
    }

    private CompletableFuture<ItemStack> issueSerial(UUID owner, KeyDraft.Published definition, GrantSource source,
            String actor) {
        KeySerial serial = new KeySerial();
        serial.serial = draw.id();
        serial.keyId = definition.spec.id;
        serial.defVersion = definition.version;
        serial.owner = owner.toString();
        serial.usesLeft = Math.max(1, definition.spec.uses);
        serial.source = source.name();
        serial.issuedBy = actor;
        serial.issuedAt = System.currentTimeMillis();
        serial.expiresAt = definition.spec.expiresAfterDays <= 0 ? 0
                : serial.issuedAt + TimeUnit.DAYS.toMillis(definition.spec.expiresAfterDays);
        KeyMarker marker = KeyMarker.signed(serial.keyId, serial.defVersion, serial.serial, SECRET_ID, secret);
        return storage.saveSerial(serial).thenApply(saved -> KeyItemFactory.create(definition, marker));
    }

    private CompletableFuture<Integer> toInbox(UUID owner, List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        long now = System.currentTimeMillis();
        return storage.updatePending(owner, inbox -> {
            for (ItemStack stack : stacks) {
                KeyMarker marker = KeyItemFactory.read(stack);
                PendingInbox.PendingReward entry = new PendingInbox.PendingReward();
                entry.fulfillmentId = "key#" + (marker == null ? draw.id() : marker.serial());
                if (inbox.contains(entry.fulfillmentId)) {
                    continue;
                }
                entry.label = marker == null ? stack.getItemId() : marker.keyId();
                entry.itemId = stack.getItemId();
                entry.quantity = stack.getQuantity();
                entry.metadata = ItemStackMetadata.toJson(stack);
                entry.createdAt = now;
                inbox.items.add(entry);
            }
            return inbox;
        }).thenApply(inbox -> stacks.size());
    }

    // ----- Revoke and inspect -------------------------------------------------

    public CompletableFuture<Boolean> revoke(String serial, String actor) {
        AtomicBoolean changed = new AtomicBoolean();
        return storage.updateSerial(serial, entry -> {
            if (entry == null || KeySerial.REVOKED.equals(entry.state) || KeySerial.REDEEMED.equals(entry.state)) {
                return null;
            }
            entry.state = KeySerial.REVOKED;
            changed.set(true);
            return entry;
        }).thenApply(entry -> {
            if (changed.get()) {
                audit.record(actor, "KEY_REVOKE", serial, entry.keyId);
                core.getEventBus().publish(new KeyRevokedEvent(serial, entry.keyId, actor));
            }
            return changed.get();
        });
    }

    public CompletableFuture<KeySerial> serial(String serial) {
        return storage.loadSerial(serial);
    }
}
