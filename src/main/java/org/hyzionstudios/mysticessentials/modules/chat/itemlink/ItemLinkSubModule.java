package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Chat item-link subsystem. Detects the configured tag in a player's message,
 * captures a read-only {@link ItemSnapshot} of their held item, and rewrites the
 * tag into a formatted, colour-coded (optionally clickable) display name in chat.
 * The icon is never rendered inline (verified 0.5.6 dead-end) — it appears only in
 * the {@link ItemDetailsPage} and {@link RecentItemLinksPage} custom UI panels,
 * reachable via a chat-link click, the {@code /iteminspect} / {@code /itemlinks}
 * commands, or the Recent Links list.
 */
public final class ItemLinkSubModule {

    private final MysticCore core;
    private ItemLinkConfig config = new ItemLinkConfig();
    private ItemSnapshotService snapshots;

    /** Outcome of expanding item tags in one message. */
    public record ExpandResult(String content, ItemSnapshot snapshot) {
    }

    public ItemLinkSubModule(MysticCore core) {
        this.core = core;
    }

    // ----- Lifecycle ------------------------------------------------------------

    public void enable(Consumer<MysticCommand> commandRegistrar) {
        config = loadConfig();
        snapshots = new ItemSnapshotService(core, config);
        commandRegistrar.accept(new ItemInspectCommand());
        commandRegistrar.accept(new ItemLinksCommand());
    }

    public void reload() {
        config = loadConfig();
        if (snapshots != null) {
            snapshots.updateConfig(config);
        }
    }

    public void invalidate(java.util.UUID player) {
        if (snapshots != null) {
            snapshots.forget(player);
        }
    }

    public boolean isEnabled() {
        return config.enabled;
    }

    public ItemSnapshotService snapshots() {
        return snapshots;
    }

    // ----- Tag expansion --------------------------------------------------------

    /**
     * Replaces up to {@code maxTagsPerMessage} occurrences of the item tag in a
     * (already sanitized) player message with the rendered item-link markup.
     * Captures the sender's held item once and reuses it for every occurrence.
     * Cheap no-op when the tag is absent, the subsystem is off, or the player
     * lacks permission.
     */
    public ExpandResult expand(PlayerRef sender, String message, String channelName) {
        if (message == null || !config.enabled || snapshots == null) {
            return new ExpandResult(message, null);
        }
        String tag = config.tag == null || config.tag.isBlank() ? "[item]" : config.tag;
        if (!message.contains(tag)) {
            return new ExpandResult(message, null);
        }
        if (config.usePermission != null && !config.usePermission.isBlank()
                && !sender.hasPermission(config.usePermission)) {
            return new ExpandResult(message, null);
        }

        Optional<ItemSnapshot> captured = snapshots.captureHeld(sender, orEmpty(channelName));
        String replacement = captured.map(this::linkMarkup).orElse("&7[no item]&r");

        StringBuilder out = new StringBuilder(message.length() + 32);
        int from = 0;
        int replaced = 0;
        int max = Math.max(1, config.maxTagsPerMessage);
        int idx;
        while (replaced < max && (idx = message.indexOf(tag, from)) >= 0) {
            out.append(message, from, idx).append(replacement);
            from = idx + tag.length();
            replaced++;
        }
        out.append(message.substring(from));
        return new ExpandResult(out.toString(), captured.orElse(null));
    }

    /** Records a shared item into each recipient's recent-links history. */
    public void recordHistory(ItemSnapshot snapshot, Collection<PlayerRef> recipients) {
        if (snapshots != null && snapshot != null) {
            snapshots.recordHistory(snapshot, recipients);
        }
    }

    /**
     * Builds the chat markup for one item link: a colour-coded, optionally clicky
     * {@code [Name ×n]} followed by a visible, typeable {@code (/itemview CODE)}
     * hint. The hint is the guaranteed path (works by typing on any input device);
     * the name-link is a bonus if the client dispatches chat command-links.
     */
    private String linkMarkup(ItemSnapshot snapshot) {
        String command = "/" + viewCommandLabel() + " " + snapshot.id;
        StringBuilder sb = new StringBuilder();
        boolean link = config.linkChatNameToInspect;
        if (link) {
            sb.append("<link:").append(command).append('>');
        }
        if (config.underlineChatName) {
            sb.append("<u>");
        }
        sb.append('<').append(normalizeColor(snapshot.rarityColor)).append('>');
        sb.append('[').append(nameText(snapshot));
        if (config.showQuantityInChat && snapshot.quantity > 1) {
            sb.append(" ×").append(snapshot.quantity);
        }
        sb.append(']');
        // Close styling so following chat text is unaffected.
        sb.append("</#>");
        if (config.underlineChatName) {
            sb.append("</u>");
        }
        if (link) {
            sb.append("</link>");
        }
        if (config.showViewCommandInChat) {
            // Visible + typeable, and also click-linked as a convenience.
            sb.append(" <link:").append(command).append(">&8(&7").append(command)
                    .append("&8)</link>");
        }
        return sb.toString();
    }

    /** The normalized view-command label (no slash, lowercase), defaulting to {@code itemview}. */
    private String viewCommandLabel() {
        String raw = config.viewCommand == null ? "" : config.viewCommand.trim().toLowerCase(Locale.ROOT);
        while (raw.startsWith("/")) {
            raw = raw.substring(1);
        }
        return raw.isBlank() ? "itemview" : raw;
    }

    /** The name portion of the chat link: a client-translated segment or a safe literal. */
    private static String nameText(ItemSnapshot snapshot) {
        if (snapshot.customName != null && !snapshot.customName.isBlank()) {
            return sanitizeInline(snapshot.customName);
        }
        if (snapshot.translationKey != null && !snapshot.translationKey.isBlank()) {
            return "<lang:" + snapshot.translationKey + ">";
        }
        return sanitizeInline(snapshot.plainName());
    }

    /** Strips characters that would break the surrounding chat markup. */
    private static String sanitizeInline(String text) {
        return text == null ? "" : text.replaceAll("[<>&\\[\\]]", "");
    }

    private static String normalizeColor(String color) {
        if (color == null || color.isBlank()) {
            return "#FFFFFF";
        }
        return color.startsWith("#") ? color : "#" + color;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    // ----- Config ---------------------------------------------------------------

    private Path configFile() {
        return core.paths().moduleExtraConfigFile("chat", "item-links.json");
    }

    private ItemLinkConfig loadConfig() {
        try {
            ItemLinkConfig loaded = Json.readFile(configFile(), ItemLinkConfig.class);
            if (loaded == null) {
                loaded = new ItemLinkConfig();
                Json.writeFile(configFile(), Json.toTree(loaded));
                core.log(Level.INFO, "Generated default modules/chat/item-links.json");
            }
            return normalize(loaded);
        } catch (Exception e) {
            core.log(Level.WARNING, "Failed to load item-links.json (keeping previous config): "
                    + e.getMessage());
            return config != null ? config : new ItemLinkConfig();
        }
    }

    private static ItemLinkConfig normalize(ItemLinkConfig loaded) {
        ItemLinkConfig defaults = new ItemLinkConfig();
        if (loaded.tag == null || loaded.tag.isBlank()) {
            loaded.tag = defaults.tag;
        }
        if (loaded.viewCommand == null || loaded.viewCommand.isBlank()) {
            loaded.viewCommand = defaults.viewCommand;
        }
        if (loaded.maxTagsPerMessage < 1) {
            loaded.maxTagsPerMessage = 1;
        } else if (loaded.maxTagsPerMessage > 10) {
            loaded.maxTagsPerMessage = 10;
        }
        if (loaded.snapshot == null) {
            loaded.snapshot = defaults.snapshot;
        }
        if (loaded.snapshot.retentionSeconds < 5) {
            loaded.snapshot.retentionSeconds = defaults.snapshot.retentionSeconds;
        }
        if (loaded.snapshot.maximumSnapshots < 1) {
            loaded.snapshot.maximumSnapshots = defaults.snapshot.maximumSnapshots;
        }
        if (loaded.history == null) {
            loaded.history = defaults.history;
        }
        if (loaded.history.maximumEntries < 1) {
            loaded.history.maximumEntries = defaults.history.maximumEntries;
        }
        if (loaded.inspectionUi == null) {
            loaded.inspectionUi = defaults.inspectionUi;
        }
        if (loaded.rarities == null || loaded.rarities.isEmpty()) {
            loaded.rarities = defaults.rarities;
        }
        if (loaded.rarityRules == null) {
            loaded.rarityRules = defaults.rarityRules;
        }
        return loaded;
    }

    // ----- Page opening ---------------------------------------------------------

    void openDetails(PlayerRef player, ItemSnapshot snapshot) {
        if (!core.platform().openPage(player, new ItemDetailsPage(core, player, snapshot, config))) {
            core.getMessageService().send(player,
                    "&cCould not open the item details UI — see the server log.");
        }
    }

    void openRecent(PlayerRef player) {
        if (!core.platform().openPage(player,
                new RecentItemLinksPage(core, player, snapshots, config))) {
            showRecentText(player);
        }
    }

    private void showRecentText(PlayerRef player) {
        List<ItemSnapshot> recent = snapshots.recent(player.getUuid());
        if (recent.isEmpty()) {
            core.getMessageService().send(player, "&7No recently shared items.");
            return;
        }
        core.getMessageService().send(player, "&8&m----&r &bRecently Shared Items &8&m----");
        for (int i = 0; i < recent.size(); i++) {
            ItemSnapshot snapshot = recent.get(i);
            String command = "/" + viewCommandLabel() + " " + snapshot.id;
            core.getMessageService().send(player, "&7" + (i + 1) + ". <link:" + command + "><"
                    + normalizeColor(snapshot.rarityColor) + ">"
                    + sanitizeInline(snapshot.plainName()) + "</#></link> &8(&7" + command + "&8)");
        }
    }

    // ----- Commands -------------------------------------------------------------

    private final class ItemInspectCommand extends MysticCommand {
        ItemInspectCommand() {
            super(ItemLinkSubModule.this.core, "iteminspect",
                    "Inspect a recently shared item.");
            addAliases("inspectitem");
            String view = viewCommandLabel();
            if (!"iteminspect".equals(view) && !"inspectitem".equals(view)) {
                addAliases(view);
            }
            allowExtraArguments();
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void run(MysticCommandSender sender) {
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            String arg = sender.arg(0).orElse("latest").trim().toLowerCase(Locale.ROOT);
            Optional<ItemSnapshot> target;
            if (arg.isBlank() || "latest".equals(arg) || "last".equals(arg)) {
                target = snapshots.latest(player.getUuid());
            } else if (arg.chars().allMatch(Character::isDigit)) {
                target = snapshots.recentAt(player.getUuid(), Integer.parseInt(arg));
            } else {
                target = snapshots.get(sender.arg(0).orElse(""));
            }
            target.ifPresentOrElse(
                    snapshot -> openDetails(player, snapshot),
                    () -> sender.reply("&7No shared item to inspect. Items expire after a while."));
        }
    }

    private final class ItemLinksCommand extends MysticCommand {
        ItemLinksCommand() {
            super(ItemLinkSubModule.this.core, "itemlinks",
                    "Browse items recently shared in chat.");
            addAliases("recentitems");
            allowExtraArguments();
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void run(MysticCommandSender sender) {
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            openRecent(player);
        }
    }
}
