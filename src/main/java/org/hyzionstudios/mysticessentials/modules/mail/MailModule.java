package org.hyzionstudios.mysticessentials.modules.mail;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.model.MailAttachment;
import org.hyzionstudios.mysticessentials.api.model.MailMessage;
import org.hyzionstudios.mysticessentials.api.service.MailService;
import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Virtual mail: send to online or offline players, inbox read/unread tracking,
 * and permission-gated bulk send. Inboxes are stored through the
 * {@link StorageService} under the {@code mail} namespace, keyed by recipient
 * UUID, so offline delivery works with any storage provider.
 */
public final class MailModule extends AbstractMysticModule implements MailService {

    private static final String NAMESPACE = "mail";
    private static final Type INBOX_TYPE = new TypeToken<ArrayList<MailMessage>>() {
    }.getType();
    private static final Type ANNOUNCEMENT_LOG_TYPE = new TypeToken<ArrayList<SentAnnouncement>>() {
    }.getType();

    private MailConfig config = new MailConfig();

    public MailModule() {
        super("mail", "Mail", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), MailConfig.class, new MailConfig());
        registerCommand(new MailCommand());
        registerCommand(new MailAdminTopCommand());
        registerEvent(
                com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent.class,
                (com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent event) ->
                        notifyUnread(event.getPlayerRef()));
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), MailConfig.class, new MailConfig());
    }

    @Override
    public void onDisable() {
        // Inboxes are persisted on every mutation; nothing to flush.
    }

    private void notifyUnread(PlayerRef player) {
        if (!config.notifyUnreadOnJoin) {
            return;
        }
        inbox(player.getUuid()).thenAccept(inbox -> {
            int unread = (int) inbox.stream().filter(m -> !m.isRead()).count();
            if (unread > 0) {
                core.getMessageService().sendKey(player, "mail-notify-join-unread",
                        Map.of("unread", Integer.toString(unread)));
            } else if (!inbox.isEmpty()) {
                core.getMessageService().sendKey(player, "mail-notify-join-any",
                        Map.of("count", Integer.toString(inbox.size())));
            }
        });
    }

    // ----- MailService -------------------------------------------------------

    private CompletableFuture<List<MailMessage>> loadInbox(UUID player) {
        StorageService storage = core.getStorageService();
        return storage.load(NAMESPACE, player.toString()).thenApply(element -> {
            List<MailMessage> inbox = element == null ? null : Json.gson().fromJson(element, INBOX_TYPE);
            return inbox != null ? inbox : new ArrayList<>();
        });
    }

    private CompletableFuture<Void> saveInbox(UUID player, List<MailMessage> inbox) {
        return core.getStorageService().save(NAMESPACE, player.toString(), Json.toTree(inbox));
    }

    @Override
    public CompletableFuture<Void> send(UUID sender, String senderName, UUID recipient, String body) {
        return deliver(recipient, MailMessage.create(sender, senderName, truncateBody(body)));
    }

    @Override
    public CompletableFuture<Void> deliver(UUID recipient, MailMessage prototype) {
        MailMessage mail = prototype.copyForDelivery();
        mail.setSubject(prototype.getSubject());
        return loadInbox(recipient).thenCompose(inbox -> {
            enforceInboxCap(inbox);
            inbox.add(mail);
            CompletableFuture<Void> saved = saveInbox(recipient, inbox);
            String senderName = mail.getSenderName();
            core.platform().findPlayer(recipient).ifPresent(ref ->
                    core.getMessageService().sendKey(ref, "mail-notify-new",
                            Map.of("sender", senderName == null || senderName.isBlank() ? "Server" : senderName)));
            return saved;
        });
    }

    private String truncateBody(String body) {
        return config.maxMessageLength > 0 && body != null && body.length() > config.maxMessageLength
                ? body.substring(0, config.maxMessageLength)
                : body;
    }

    /**
     * Keeps the inbox under {@code maxInboxSize}, dropping the oldest already-read
     * message that has nothing left to claim first, then the oldest message with
     * no unclaimed rewards, and only as a last resort the oldest overall — so mail
     * that still holds unclaimed items/commands is never silently evicted.
     */
    private void enforceInboxCap(List<MailMessage> inbox) {
        int max = config.maxInboxSize;
        if (max <= 0) {
            return;
        }
        while (inbox.size() >= max) {
            inbox.remove(dropCandidate(inbox));
        }
    }

    private static int dropCandidate(List<MailMessage> inbox) {
        for (int i = 0; i < inbox.size(); i++) {
            if (inbox.get(i).isRead() && !inbox.get(i).isClaimable()) {
                return i;
            }
        }
        for (int i = 0; i < inbox.size(); i++) {
            if (!inbox.get(i).isClaimable()) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public CompletableFuture<Integer> sendAll(String senderName, String permission, String body) {
        List<PlayerRef> recipients = core.platform().onlinePlayers().stream()
                .filter(ref -> permission == null || ref.hasPermission(permission))
                .toList();
        List<CompletableFuture<Void>> sends = new ArrayList<>();
        for (PlayerRef ref : recipients) {
            sends.add(send(null, senderName, ref.getUuid(), body));
        }
        return CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                .thenApply(v -> recipients.size());
    }

    @Override
    public CompletableFuture<List<MailMessage>> inbox(UUID player) {
        return loadInbox(player);
    }

    @Override
    public CompletableFuture<Integer> unreadCount(UUID player) {
        return loadInbox(player).thenApply(inbox ->
                (int) inbox.stream().filter(m -> !m.isRead()).count());
    }

    @Override
    public CompletableFuture<Boolean> markRead(UUID player, String mailId) {
        return loadInbox(player).thenCompose(inbox -> {
            Optional<MailMessage> mail = inbox.stream().filter(m -> m.getId().equals(mailId)).findFirst();
            if (mail.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            mail.get().setRead(true);
            return saveInbox(player, inbox).thenApply(v -> true);
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID player, String mailId) {
        return loadInbox(player).thenCompose(inbox -> {
            boolean removed = inbox.removeIf(m -> m.getId().equals(mailId));
            return removed ? saveInbox(player, inbox).thenApply(v -> true)
                    : CompletableFuture.completedFuture(false);
        });
    }

    @Override
    public CompletableFuture<Void> clear(UUID player) {
        return saveInbox(player, new ArrayList<>());
    }

    @Override
    public CompletableFuture<Optional<MailMessage>> getMessage(UUID player, String mailId) {
        return loadInbox(player).thenApply(inbox ->
                inbox.stream().filter(m -> m.getId().equals(mailId)).findFirst());
    }

    @Override
    public CompletableFuture<Integer> markAllRead(UUID player) {
        return loadInbox(player).thenCompose(inbox -> {
            int flipped = 0;
            for (MailMessage mail : inbox) {
                if (!mail.isRead()) {
                    mail.setRead(true);
                    flipped++;
                }
            }
            if (flipped == 0) {
                return CompletableFuture.completedFuture(0);
            }
            int total = flipped;
            return saveInbox(player, inbox).thenApply(v -> total);
        });
    }

    @Override
    public CompletableFuture<Boolean> setArchived(UUID player, String mailId, boolean archived) {
        return mutate(player, mailId, mail -> {
            mail.setArchived(archived);
            if (archived) {
                mail.setDeleted(false);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> setDeleted(UUID player, String mailId, boolean deleted) {
        return mutate(player, mailId, mail -> {
            mail.setDeleted(deleted);
            if (deleted) {
                mail.setArchived(false);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> markClaimed(UUID player, String mailId) {
        return mutate(player, mailId, mail -> mail.setClaimed(true));
    }

    /** Loads the inbox, applies {@code mutation} to the matching mail, and saves; false if not found. */
    private CompletableFuture<Boolean> mutate(UUID player, String mailId, Consumer<MailMessage> mutation) {
        return loadInbox(player).thenCompose(inbox -> {
            Optional<MailMessage> match = inbox.stream().filter(m -> m.getId().equals(mailId)).findFirst();
            if (match.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            mutation.accept(match.get());
            return saveInbox(player, inbox).thenApply(v -> true);
        });
    }

    // ----- Item attachments & rewards (server-authoritative, world thread) -----

    /** A pending reward pick from the compose UI: an item id and a quantity. */
    record ItemPick(String itemId, int quantity) {
    }

    /** @return {@code true} if this item id may never be mailed. */
    private boolean isBlocked(String itemId) {
        if (itemId == null || config.blockedItemIds == null || config.blockedItemIds.isEmpty()) {
            return itemId == null;
        }
        String needle = itemId.toLowerCase(Locale.ROOT);
        for (String blocked : config.blockedItemIds) {
            if (blocked != null && blocked.toLowerCase(Locale.ROOT).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private static List<ItemContainer> sources(Inventory inventory) {
        List<ItemContainer> sources = new ArrayList<>();
        if (inventory.getHotbar() != null) {
            sources.add(inventory.getHotbar());
        }
        if (inventory.getStorage() != null) {
            sources.add(inventory.getStorage());
        }
        if (inventory.getBackpack() != null) {
            sources.add(inventory.getBackpack());
        }
        return sources;
    }

    private static int totalOf(List<ItemContainer> containers, String itemId) {
        int total = 0;
        for (ItemContainer container : containers) {
            for (short i = 0; i < container.getCapacity(); i++) {
                ItemStack stack = container.getItemStack(i);
                if (stack != null && !stack.isEmpty() && itemId.equals(stack.getItemId())) {
                    total += stack.getQuantity();
                }
            }
        }
        return total;
    }

    private static int freeSlots(List<ItemContainer> containers) {
        int free = 0;
        for (ItemContainer container : containers) {
            for (short i = 0; i < container.getCapacity(); i++) {
                ItemStack stack = container.getItemStack(i);
                if (stack == null || stack.isEmpty()) {
                    free++;
                }
            }
        }
        return free;
    }

    /**
     * Reads the player's storage + backpack on their world thread and returns the
     * distinct mailable items with their total quantities (the compose picker
     * source). Never fabricates: it reflects exactly what the player holds now.
     */
    CompletableFuture<List<ItemPick>> readInventory(PlayerRef player) {
        CompletableFuture<List<ItemPick>> out = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            try {
                Player entityPlayer = store.getComponent(entity, Player.getComponentType());
                Inventory inventory = entityPlayer == null ? null : entityPlayer.getInventory();
                if (inventory == null) {
                    out.complete(List.of());
                    return;
                }
                LinkedHashMap<String, Integer> totals = new LinkedHashMap<>();
                for (ItemContainer container : sources(inventory)) {
                    for (short i = 0; i < container.getCapacity(); i++) {
                        ItemStack stack = container.getItemStack(i);
                        if (stack == null || stack.isEmpty() || isBlocked(stack.getItemId())) {
                            continue;
                        }
                        totals.merge(stack.getItemId(), stack.getQuantity(), Integer::sum);
                    }
                }
                List<ItemPick> picks = new ArrayList<>();
                totals.forEach((id, qty) -> picks.add(new ItemPick(id, qty)));
                out.complete(picks);
            } catch (Throwable t) {
                core.log(Level.WARNING, "[mail] inventory read failed: " + t);
                out.complete(List.of());
            }
        });
        if (!dispatched) {
            out.complete(List.of());
        }
        return out;
    }

    /**
     * Removes the requested item quantities from the sender's inventory on their
     * world thread, returning the serialized attachments — or {@code null} if any
     * pick is blocked or the sender no longer holds enough (verify-then-remove, so
     * a shortfall removes nothing). This is the anti-dupe boundary: items only
     * exist on the mail once they have left the sender.
     */
    private CompletableFuture<List<MailAttachment>> consumeItems(PlayerRef sender, List<ItemPick> picks) {
        CompletableFuture<List<MailAttachment>> out = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(sender, (store, entity, world) -> {
            try {
                Player entityPlayer = store.getComponent(entity, Player.getComponentType());
                Inventory inventory = entityPlayer == null ? null : entityPlayer.getInventory();
                if (inventory == null) {
                    out.complete(null);
                    return;
                }
                List<ItemContainer> containers = sources(inventory);
                for (ItemPick pick : picks) {
                    if (isBlocked(pick.itemId()) || pick.quantity() <= 0
                            || totalOf(containers, pick.itemId()) < pick.quantity()) {
                        out.complete(null);
                        return;
                    }
                }
                List<MailAttachment> attachments = new ArrayList<>();
                for (ItemPick pick : picks) {
                    int remaining = pick.quantity();
                    for (ItemContainer container : containers) {
                        for (short i = 0; i < container.getCapacity() && remaining > 0; i++) {
                            ItemStack stack = container.getItemStack(i);
                            if (stack == null || stack.isEmpty() || !pick.itemId().equals(stack.getItemId())) {
                                continue;
                            }
                            int take = Math.min(remaining, stack.getQuantity());
                            MailAttachment attachment = MailItemCodec.toStored(stack, take);
                            if (attachment != null) {
                                attachments.add(attachment);
                            }
                            int left = stack.getQuantity() - take;
                            if (left <= 0) {
                                container.setItemStackForSlot(i, ItemStack.EMPTY);
                            } else {
                                container.setItemStackForSlot(i, new ItemStack(stack.getItemId(), left,
                                        stack.getDurability(), stack.getMaxDurability(), stack.getMetadata()));
                            }
                            remaining -= take;
                        }
                    }
                }
                out.complete(attachments);
            } catch (Throwable t) {
                core.log(Level.WARNING, "[mail] item consume failed: " + t);
                out.complete(null);
            }
        });
        if (!dispatched) {
            out.complete(null);
        }
        return out;
    }

    /**
     * Claims a mail's rewards once: gives the escrowed items to the recipient's
     * inventory (refusing if there is not enough room) and runs any reward
     * commands as console, then flips the mail to claimed. Runs on the recipient's
     * world thread.
     */
    void claimRewards(PlayerRef player, String mailId, Runnable refresh) {
        getMessage(player.getUuid(), mailId).thenAccept(opt -> {
            if (opt.isEmpty() || !opt.get().isClaimable()) {
                core.getMessageService().sendKey(player, "mail-nothing-to-claim");
                refresh.run();
                return;
            }
            List<MailAttachment> items = new ArrayList<>(opt.get().items());
            List<String> commands = new ArrayList<>(opt.get().commands());
            boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
                try {
                    if (!items.isEmpty()) {
                        Player entityPlayer = store.getComponent(entity, Player.getComponentType());
                        Inventory inventory = entityPlayer == null ? null : entityPlayer.getInventory();
                        if (inventory == null || freeSlots(sources(inventory)) < items.size()) {
                            core.getMessageService().sendKey(player, "mail-claim-no-space");
                            refresh.run();
                            return;
                        }
                        for (MailAttachment attachment : items) {
                            Player.giveItem(MailItemCodec.toLive(attachment), entity, store);
                        }
                    }
                    for (String command : commands) {
                        String resolved = command
                                .replace("{player}", player.getUsername())
                                .replace("{uuid}", player.getUuid().toString());
                        if (resolved.startsWith("/")) {
                            resolved = resolved.substring(1);
                        }
                        core.platform().dispatchConsoleCommand(resolved);
                    }
                    markClaimed(player.getUuid(), mailId).thenRun(() -> {
                        core.getMessageService().sendKey(player, "mail-claimed");
                        refresh.run();
                    });
                } catch (Throwable t) {
                    core.log(Level.WARNING, "[mail] claim failed: " + t);
                    refresh.run();
                }
            });
            if (!dispatched) {
                refresh.run();
            }
        });
    }

    // ----- Custom UI ----------------------------------------------------------

    private static final String SENT_NAMESPACE = "mail_sent";

    /** Opens the Mail UI on the default Inbox view. */
    void openMailUi(PlayerRef player) {
        openMailUi(player, MailPages.View.INBOX, null, "", 0, MailPages.ComposeDraft.empty());
    }

    /**
     * Loads the data a view needs (inbox always, plus the Sent log or the compose
     * item picker where relevant) off the page build, then opens the Mail UI. The
     * page is constructed with an inbox snapshot (storage is async; page building
     * is not) and re-opened to refresh after every action.
     */
    void openMailUi(PlayerRef player, MailPages.View view, String selectedId, String search, int page,
            MailPages.ComposeDraft draft) {
        MailPages.ComposeDraft safeDraft = draft == null ? MailPages.ComposeDraft.empty() : draft;
        inbox(player.getUuid()).thenAccept(inbox -> {
            switch (view) {
                case SENT -> sentInbox(player.getUuid()).thenAccept(sent ->
                        openPage(player, inbox, sent, List.of(), view, selectedId, search, page, safeDraft));
                case COMPOSE -> readInventory(player).thenAccept(items ->
                        openPage(player, inbox, List.of(), filterPicks(items, safeDraft.pickerSearch()),
                                view, selectedId, search, page, safeDraft));
                default -> openPage(player, inbox, List.of(), List.of(),
                        view, selectedId, search, page, safeDraft);
            }
        });
    }

    private void openPage(PlayerRef player, List<MailMessage> inbox, List<MailMessage> sent,
            List<ItemPick> pickerItems, MailPages.View view, String selectedId, String search, int page,
            MailPages.ComposeDraft draft) {
        core.platform().openPage(player, new MailPages.MailPage(core, this, player, inbox, sent,
                pickerItems, view, selectedId, search, page, draft));
    }

    /** Opens the standalone mail admin center ({@code /mailadmin}) with a fresh draft. */
    void openMailAdminUi(PlayerRef player) {
        openMailAdminUi(player, MailAdminPages.AdminDraft.empty());
    }

    /**
     * Loads the announcement history and the (search-filtered) item catalogue off
     * the page build, then opens the admin center. Re-opened to refresh after
     * every action, carrying the in-progress {@link MailAdminPages.AdminDraft}.
     */
    void openMailAdminUi(PlayerRef player, MailAdminPages.AdminDraft draft) {
        MailAdminPages.AdminDraft safeDraft = draft == null ? MailAdminPages.AdminDraft.empty() : draft;
        announcementLog().thenAccept(log -> core.platform().openPage(player,
                new MailAdminPages.MailAdminPage(core, this, player, log,
                        catalogPicks(safeDraft.pickerSearch()), safeDraft)));
    }

    /** Filters an inventory picker list by a case-insensitive item-id substring. */
    private static List<ItemPick> filterPicks(List<ItemPick> picks, String query) {
        if (query == null || query.isBlank()) {
            return picks;
        }
        String needle = query.toLowerCase(Locale.ROOT).trim();
        List<ItemPick> filtered = new ArrayList<>();
        for (ItemPick pick : picks) {
            if (pick.itemId().toLowerCase(Locale.ROOT).contains(needle)) {
                filtered.add(pick);
            }
        }
        return filtered;
    }

    /** The announcement picker source: any registered item (quantity chosen on add). */
    private List<ItemPick> catalogPicks(String query) {
        List<ItemPick> picks = new ArrayList<>();
        for (String itemId : org.hyzionstudios.mysticessentials.modules.playervaults.service.VaultItemCatalog
                .search(query, 60)) {
            if (!isBlocked(itemId)) {
                picks.add(new ItemPick(itemId, 1));
            }
        }
        return picks;
    }

    int maxAttachments() {
        return config.maxAttachments;
    }

    int pageSize() {
        return Math.max(1, config.pageSize);
    }

    boolean allowPlayerItemAttachments() {
        return config.allowPlayerItemAttachments;
    }

    boolean allowAnnouncementCommands() {
        return config.allowAnnouncementCommands;
    }

    // ----- Sent folder --------------------------------------------------------

    CompletableFuture<List<MailMessage>> sentInbox(UUID player) {
        return core.getStorageService().load(SENT_NAMESPACE, player.toString()).thenApply(element -> {
            List<MailMessage> list = element == null ? null : Json.gson().fromJson(element, INBOX_TYPE);
            return list != null ? list : new ArrayList<>();
        });
    }

    private void recordSent(UUID sender, MailMessage prototype, String recipientLabel) {
        sentInbox(sender).thenCompose(list -> {
            MailMessage copy = prototype.copyForDelivery();
            copy.setRead(true);
            copy.setRecipientName(recipientLabel);
            list.add(copy);
            while (config.maxInboxSize > 0 && list.size() > config.maxInboxSize) {
                list.remove(0);
            }
            return core.getStorageService().save(SENT_NAMESPACE, sender.toString(), Json.toTree(list));
        });
    }

    // ----- Compose & announce (from the UI) -----------------------------------

    /** Resolves a recipient by name (online, else offline-by-name when permitted); messages on failure. */
    private CompletableFuture<Optional<UUID>> resolveRecipient(PlayerRef sender, String targetName) {
        Optional<PlayerRef> online = core.platform().findPlayerByName(targetName)
                .filter(ref -> core.vanish().canSee(sender.getUuid(), ref.getUuid()));
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(Optional.of(online.get().getUuid()));
        }
        if (!sender.hasPermission(Permissions.MAIL_SEND_OFFLINE)) {
            core.getMessageService().sendKey(sender, "player-not-found");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return core.getPlayerProfileService().resolveUuid(targetName).thenApply(resolved -> {
            if (resolved.isEmpty()) {
                core.getMessageService().sendKey(sender, "mail-player-never-joined",
                        Map.of("player", targetName));
            }
            return resolved;
        });
    }

    /** Sends composed player mail, consuming any attached items from the sender first. */
    void sendComposedMail(PlayerRef sender, MailPages.ComposeDraft draft, Runnable refresh) {
        if (!sender.hasPermission(Permissions.MAIL_SEND)) {
            core.getMessageService().sendKey(sender, "no-permission");
            refresh.run();
            return;
        }
        String target = draft.recipient();
        if (target == null || target.isBlank() || draft.body() == null || draft.body().isBlank()) {
            core.getMessageService().sendKey(sender, "mail-send-player-message");
            refresh.run();
            return;
        }
        List<ItemPick> picks = draft.picks();
        if (!picks.isEmpty() && (!config.allowPlayerItemAttachments
                || !sender.hasPermission(Permissions.MAIL_ATTACH_ITEMS))) {
            core.getMessageService().sendKey(sender, "no-permission");
            refresh.run();
            return;
        }
        resolveRecipient(sender, target).thenAccept(opt -> {
            if (opt.isEmpty()) {
                refresh.run();
                return;
            }
            UUID recipient = opt.get();
            if (picks.isEmpty()) {
                MailMessage proto = MailMessage.create(sender.getUuid(), sender.getUsername(),
                        truncateBody(draft.body()));
                finishSend(sender, recipient, target, proto, refresh);
            } else {
                consumeItems(sender, picks).thenAccept(attachments -> {
                    if (attachments == null) {
                        core.getMessageService().sendKey(sender, "mail-attach-insufficient");
                        refresh.run();
                        return;
                    }
                    MailMessage proto = MailMessage.create(sender.getUuid(), sender.getUsername(),
                            truncateBody(draft.body()));
                    proto.setItems(attachments);
                    finishSend(sender, recipient, target, proto, refresh);
                });
            }
        });
    }

    private void finishSend(PlayerRef sender, UUID recipient, String targetLabel, MailMessage proto,
            Runnable refresh) {
        deliver(recipient, proto).thenRun(() -> {
            recordSent(sender.getUuid(), proto, targetLabel);
            core.getMessageService().sendKey(sender, "mail-sent", Map.of("player", targetLabel));
            refresh.run();
        });
    }

    // ----- Admin center: audiences, broadcast & history -----------------------

    private static final String ANNOUNCEMENT_NAMESPACE = "mail_announcements";
    private static final String ANNOUNCEMENT_LOG_KEY = "log";
    private static final int ANNOUNCEMENT_LOG_CAP = 50;

    /** Who an admin broadcast is delivered to. */
    enum Audience {
        /** Every player online right now. */
        ONLINE("Online now"),
        /** Every player who has ever joined (offline included). */
        KNOWN("All known"),
        /** Online players holding a permission node ({@code param}). */
        PERMISSION("Permission"),
        /** A single named player, online or offline ({@code param}). */
        PLAYER("One player");

        final String label;

        Audience(String label) {
            this.label = label;
        }

        boolean needsParam() {
            return this == PERMISSION || this == PLAYER;
        }

        static Audience parse(String name) {
            try {
                return Audience.valueOf(name);
            } catch (RuntimeException e) {
                return ONLINE;
            }
        }
    }

    /**
     * Composes and delivers an admin broadcast to the chosen {@link Audience}
     * (item + command rewards), records it in the announcement history, and
     * refreshes the caller's UI. A single-player audience is delivered as normal
     * personal mail (Inbox); every other audience as an announcement.
     */
    void sendAdminBroadcast(PlayerRef sender, Audience audience, String param, String subject, String body,
            List<ItemPick> picks, List<String> commands, Runnable refresh) {
        if (!sender.hasPermission(Permissions.MAIL_ANNOUNCE)) {
            core.getMessageService().sendKey(sender, "no-permission");
            refresh.run();
            return;
        }
        if (body == null || body.isBlank()) {
            core.getMessageService().sendKey(sender, "mail-sendall-message");
            refresh.run();
            return;
        }
        MailMessage proto = MailMessage.create(sender.getUuid(), sender.getUsername(), truncateBody(body));
        proto.setType(audience == Audience.PLAYER ? MailMessage.TYPE_NORMAL : MailMessage.TYPE_ANNOUNCEMENT);
        proto.setSubject(subject == null || subject.isBlank() ? null : subject.trim());
        List<MailAttachment> attachments = new ArrayList<>();
        for (ItemPick pick : picks) {
            if (!isBlocked(pick.itemId())) {
                attachments.add(new MailAttachment(pick.itemId(), Math.max(1, pick.quantity()), 0, 0, null));
            }
        }
        proto.setItems(attachments);
        if (config.allowAnnouncementCommands && commands != null) {
            proto.setCommands(new ArrayList<>(commands));
        }

        resolveAudience(sender, audience, param).thenAccept(recipients -> {
            if (recipients == null) {
                refresh.run(); // failure already messaged (missing node / unknown player)
                return;
            }
            deliverInBatches(recipients, proto, 0).thenRun(() -> {
                recordAnnouncement(SentAnnouncement.of(sender.getUsername(), audience, param,
                        recipients.size(), proto));
                core.getMessageService().sendKey(sender, "mail-announcement-sent",
                        Map.of("count", Integer.toString(recipients.size())));
                refresh.run();
            });
        });
    }

    /**
     * Delivers {@code proto} to {@code recipients} one batch at a time (batch size
     * from config), each batch completing before the next starts — so a broadcast
     * to a large offline player base never opens thousands of concurrent writes.
     */
    private CompletableFuture<Void> deliverInBatches(List<UUID> recipients, MailMessage proto, int from) {
        if (from >= recipients.size()) {
            return CompletableFuture.completedFuture(null);
        }
        int size = Math.max(1, config.broadcastBatchSize);
        int to = Math.min(from + size, recipients.size());
        List<CompletableFuture<Void>> batch = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            batch.add(deliver(recipients.get(i), proto));
        }
        return CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new))
                .thenCompose(v -> deliverInBatches(recipients, proto, to));
    }

    /** Resolves an audience to recipient UUIDs; {@code null} means a validation failure was messaged. */
    private CompletableFuture<List<UUID>> resolveAudience(PlayerRef sender, Audience audience, String param) {
        return switch (audience) {
            case ONLINE -> CompletableFuture.completedFuture(onlineUuids(null));
            case PERMISSION -> {
                if (param == null || param.isBlank()) {
                    core.getMessageService().sendKey(sender, "mail-audience-need-node");
                    yield CompletableFuture.completedFuture(null);
                }
                yield CompletableFuture.completedFuture(onlineUuids(param.trim()));
            }
            case PLAYER -> {
                if (param == null || param.isBlank()) {
                    core.getMessageService().sendKey(sender, "mail-audience-need-player");
                    yield CompletableFuture.completedFuture(null);
                }
                yield resolveRecipient(sender, param.trim())
                        .thenApply(opt -> opt.<List<UUID>>map(List::of).orElse(null));
            }
            case KNOWN -> core.getPlayerProfileService().knownPlayerUuids();
        };
    }

    private List<UUID> onlineUuids(String permission) {
        List<UUID> uuids = new ArrayList<>();
        for (PlayerRef ref : core.platform().onlinePlayers()) {
            if (permission == null || ref.hasPermission(permission)) {
                uuids.add(ref.getUuid());
            }
        }
        return uuids;
    }

    // ----- Announcement history -----------------------------------------------

    CompletableFuture<List<SentAnnouncement>> announcementLog() {
        return core.getStorageService().load(ANNOUNCEMENT_NAMESPACE, ANNOUNCEMENT_LOG_KEY).thenApply(element -> {
            List<SentAnnouncement> log = element == null ? null
                    : Json.gson().fromJson(element, ANNOUNCEMENT_LOG_TYPE);
            return log != null ? log : new ArrayList<>();
        });
    }

    private void recordAnnouncement(SentAnnouncement entry) {
        announcementLog().thenCompose(log -> {
            log.add(entry);
            while (log.size() > ANNOUNCEMENT_LOG_CAP) {
                log.remove(0);
            }
            return core.getStorageService().save(ANNOUNCEMENT_NAMESPACE, ANNOUNCEMENT_LOG_KEY, Json.toTree(log));
        });
    }

    /** Re-broadcasts a past announcement (by id) to its original audience. */
    void resendAnnouncement(PlayerRef sender, String announcementId, Runnable refresh) {
        announcementLog().thenAccept(log -> {
            SentAnnouncement entry = log.stream()
                    .filter(a -> a.id != null && a.id.equals(announcementId)).findFirst().orElse(null);
            if (entry == null) {
                core.getMessageService().sendKey(sender, "mail-announcement-missing");
                refresh.run();
                return;
            }
            List<ItemPick> picks = new ArrayList<>();
            for (MailAttachment item : entry.items()) {
                picks.add(new ItemPick(item.itemId, Math.max(1, item.quantity)));
            }
            sendAdminBroadcast(sender, Audience.parse(entry.audience), entry.param,
                    entry.subject, entry.body, picks, new ArrayList<>(entry.commands()), refresh);
        });
    }

    // ----- Command -----------------------------------------------------------

    /** {@code /mailadmin} opens the standalone mail admin center (announcements + history). */
    private final class MailAdminTopCommand extends MysticCommand {
        MailAdminTopCommand() {
            super(MailModule.this.core, "mailadmin", "Open the mail admin center.");
            requirePermission(Permissions.MAIL_ANNOUNCE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openMailAdminUi(sender.player().orElseThrow());
        }
    }

    /**
     * {@code /mail} with no args opens the Mail UI; text subcommands remain for
     * quick use: inbox / read &lt;id&gt; / send &lt;player&gt; &lt;msg&gt; /
     * sendall &lt;msg&gt; / delete &lt;id&gt; / clear.
     */
    private final class MailCommand extends MysticCommand {
        MailCommand() {
            super(MailModule.this.core, "mail", "Send and read mail.");
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_USE);
            addSubCommand(new MailInboxCommand());
            addSubCommand(new MailUiCommand());
            addSubCommand(new MailReadCommand());
            addSubCommand(new MailSendCommand());
            addSubCommand(new MailSendAllCommand());
            addSubCommand(new MailDeleteCommand());
            addSubCommand(new MailClearCommand());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openMailUi(sender.player().orElseThrow());
        }

        private void showInbox(MysticCommandSender sender) {
            inbox(sender.uuid()).thenAccept(inbox -> {
                if (inbox.isEmpty()) {
                    sender.replyKey("mail-inbox-empty");
                    return;
                }
                sender.replyKey("mail-inbox-header", Map.of("count", Integer.toString(inbox.size())));
                for (MailMessage mail : inbox) {
                    sender.replyKey("mail-inbox-entry", Map.of(
                            "read_color", mail.isRead() ? "&8" : "&e",
                            "id", shortId(mail.getId()),
                            "sender", mail.getSenderName() == null ? "Server" : mail.getSenderName(),
                            "body", mail.getBody() == null ? "" : mail.getBody()));
                }
            });
        }

        private void sendMail(MysticCommandSender sender, String targetName, String body) {
            if (!sender.hasPermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND)) {
                sender.replyKey("no-permission");
                return;
            }
            Optional<PlayerRef> online = core.platform().findPlayerByName(targetName);
            if (online.isPresent()) {
                send(sender.uuid(), sender.name(), online.get().getUuid(), body)
                        .thenRun(() -> sender.replyKey("mail-sent", Map.of("player", targetName)));
            } else if (sender.hasPermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND_OFFLINE)) {
                // Offline delivery: resolve the name via our username index, then write to
                // the recipient's stored inbox (keyed by UUID) so it works while they are offline.
                core.getPlayerProfileService().resolveUuid(targetName).thenAccept(resolved -> {
                    if (resolved.isPresent()) {
                        send(sender.uuid(), sender.name(), resolved.get(), body)
                                .thenRun(() -> sender.replyKey("mail-sent-offline", Map.of("player", targetName)));
                    } else {
                        sender.replyKey("mail-player-never-joined", Map.of("player", targetName));
                    }
                });
            } else {
                sender.replyKey("player-not-found");
            }
        }

        private void sendAllMail(MysticCommandSender sender, String body) {
            if (!sender.hasPermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND_ALL)) {
                sender.replyKey("no-permission");
                return;
            }
            sendAll(sender.name(), null, body)
                    .thenAccept(count -> sender.replyKey("mail-sent-all", Map.of("count", Integer.toString(count))));
        }

        private void deleteMail(MysticCommandSender sender, String shortId) {
            inbox(sender.uuid()).thenAccept(list -> list.stream()
                    .filter(m -> shortId(m.getId()).equalsIgnoreCase(shortId))
                    .findFirst()
                    .ifPresentOrElse(
                            m -> delete(sender.uuid(), m.getId()).thenRun(() -> sender.replyKey("mail-deleted")),
                            () -> sender.replyKey("mail-missing", Map.of("id", shortId))));
        }

        private void markReadByShortId(MysticCommandSender sender, String shortId) {
            inbox(sender.uuid()).thenAccept(list -> list.stream()
                    .filter(m -> shortId(m.getId()).equalsIgnoreCase(shortId))
                    .findFirst()
                    .ifPresentOrElse(m -> markRead(sender.uuid(), m.getId()).thenRun(() ->
                            sender.replyKey("mail-read-line", Map.of(
                                    "sender", m.getSenderName() == null ? "Server" : m.getSenderName(),
                                    "body", m.getBody() == null ? "" : m.getBody()))),
                            () -> sender.replyKey("mail-missing", Map.of("id", shortId))));
        }

        private final class MailInboxCommand extends MysticCommand {
            MailInboxCommand() {
                super(MailModule.this.core, "inbox", "List your mail.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                showInbox(sender);
            }
        }

        private final class MailUiCommand extends MysticCommand {
            MailUiCommand() {
                super(MailModule.this.core, "ui", "Open the mail UI.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                if (!sender.isPlayer()) {
                    sender.replyKey("player-only");
                    return;
                }
                openMailUi(sender.player().orElseThrow());
            }
        }

        private final class MailReadCommand extends MysticCommand {
            private final RequiredArg<String> id = withRequiredArg("id", "Mail id", ArgTypes.STRING);

            MailReadCommand() {
                super(MailModule.this.core, "read", "Read a mail message.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                markReadByShortId(sender, sender.get(id));
            }
        }

        private final class MailSendCommand extends MysticCommand {
            private final RequiredArg<String> player = withRequiredArg("player", "Recipient player", ArgTypes.STRING)
                    .suggest((commandSender, input, index, result) ->
                            core.platform().onlinePlayers().forEach(ref -> result.suggest(ref.getUsername())));
            private final RequiredArg<String> message =
                    withRequiredArg("message", "Message", ArgTypes.GREEDY_STRING);

            MailSendCommand() {
                super(MailModule.this.core, "send", "Send mail to a player.");
                requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND);
            }

            @Override
            protected void run(MysticCommandSender sender) {
                sendMail(sender, sender.get(player), sender.get(message));
            }
        }

        private final class MailSendAllCommand extends MysticCommand {
            private final RequiredArg<String> message =
                    withRequiredArg("message", "Message", ArgTypes.GREEDY_STRING);

            MailSendAllCommand() {
                super(MailModule.this.core, "sendall", "Send mail to all online players.");
                requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND_ALL);
            }

            @Override
            protected void run(MysticCommandSender sender) {
                sendAllMail(sender, sender.get(message));
            }
        }

        private final class MailDeleteCommand extends MysticCommand {
            private final RequiredArg<String> id = withRequiredArg("id", "Mail id", ArgTypes.STRING);

            MailDeleteCommand() {
                super(MailModule.this.core, "delete", "Delete a mail message.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                deleteMail(sender, sender.get(id));
            }
        }

        private final class MailClearCommand extends MysticCommand {
            MailClearCommand() {
                super(MailModule.this.core, "clear", "Clear your inbox.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                clear(sender.uuid()).thenRun(() -> sender.replyKey("mail-cleared"));
            }
        }
    }

    private static String shortId(String id) {
        return id.substring(0, 8);
    }
}
