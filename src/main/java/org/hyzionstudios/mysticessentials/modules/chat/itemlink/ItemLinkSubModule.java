package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.item.ItemInspectionService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.item.ItemViewConfig;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.chat.ChatTokens;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The chat side of item sharing: detects the configured tag, captures a
 * server-owned {@link ItemSnapshot} of the sender's held item, and emits a
 * structured token in its place.
 *
 * <p>Crucially, {@link #expand} produces a <b>token</b>, not markup. The token
 * becomes a coloured, clickable name only in {@link #renderToken}, at the last
 * step before the line reaches a client. Everything in between — the routing
 * layer, the cross-server relay, the publish hook, the logs — sees an inert
 * token and renders it as {@code [Scarlet Requiem]} through
 * {@link ChatTokens#toPlainText(String, java.util.function.Function)}. Raw
 * {@code <link>} markup therefore has no path into chat at all.</p>
 */
public final class ItemLinkSubModule {

    private final MysticCore core;
    private final ItemInspectionService inspection;

    private ItemLinkConfig config = new ItemLinkConfig();
    private ItemViewConfig viewConfig = new ItemViewConfig().normalized();
    private ItemSnapshotService snapshots;

    /** Outcome of expanding item tags in one message. */
    public record ExpandResult(String content, ItemSnapshot snapshot) {
    }

    public ItemLinkSubModule(MysticCore core, ItemInspectionService inspection) {
        this.core = core;
        this.inspection = inspection;
    }

    // ----- Lifecycle ------------------------------------------------------------

    public void enable(Consumer<MysticCommand> commandRegistrar) {
        config = loadConfig();
        viewConfig = loadViewConfig();
        snapshots = new ItemSnapshotService(core, inspection, viewConfig);
        commandRegistrar.accept(new ItemInspectCommand());
        commandRegistrar.accept(new ItemLinksCommand());
    }

    public void reload() {
        config = loadConfig();
        viewConfig = loadViewConfig();
        if (snapshots != null) {
            snapshots.updateConfig(viewConfig);
        }
    }

    public void invalidate(UUID player) {
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

    public ItemViewConfig viewConfig() {
        return viewConfig;
    }

    // ----- Tag expansion --------------------------------------------------------

    /**
     * Replaces up to {@code maxTagsPerMessage} occurrences of the item tag with a
     * structured item token. Captures the sender's held item once and reuses it
     * for every occurrence. Cheap no-op when the tag is absent, the subsystem is
     * off, or the player lacks permission.
     */
    public ExpandResult expand(PlayerRef sender, String message, String channelName) {
        if (message == null || !config.enabled || snapshots == null) {
            return new ExpandResult(message, null);
        }
        String tag = config.tag;
        if (!message.contains(tag)) {
            return new ExpandResult(message, null);
        }
        if (config.usePermission != null && !config.usePermission.isBlank()
                && !sender.hasPermission(config.usePermission)) {
            return new ExpandResult(message, null);
        }

        Optional<ItemSnapshot> captured = snapshots.captureHeld(sender, orEmpty(channelName));
        // With nothing in hand there is no snapshot to reference, so this branch
        // emits literal text rather than a token that could never resolve.
        String replacement = captured
                .map(snapshot -> ChatTokens.itemToken(snapshot.id))
                .orElse(config.noItemLabel);

        StringBuilder out = new StringBuilder(message.length() + 32);
        int from = 0;
        int replaced = 0;
        int max = Math.max(1, config.maxTagsPerMessage);
        int index;
        while (replaced < max && (index = message.indexOf(tag, from)) >= 0) {
            out.append(message, from, index).append(replacement);
            from = index + tag.length();
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
     * Renders one item token into chat markup — the only place item markup is
     * ever produced.
     *
     * <p>Every failure mode resolves to clean, readable text: an expired snapshot
     * reads {@code [Item Link Expired]}, a missing one {@code [Item Unavailable]}.
     * There is no path here that falls back to emitting the raw token or an
     * unclosed tag.</p>
     */
    public String renderToken(String snapshotId) {
        if (snapshots == null) {
            return config.unavailableLabel;
        }
        ItemSnapshot snapshot = snapshots.get(snapshotId).orElse(null);
        if (snapshot == null) {
            // Distinguish "you were too slow" from "this never existed": a code
            // that is well-formed but unknown is far more likely to be expired.
            return isWellFormedCode(snapshotId) ? config.expiredLabel : config.unavailableLabel;
        }
        return linkMarkup(snapshot);
    }

    /** The plain label for an item token, used by every non-chat sink. */
    public String plainLabel(String snapshotId) {
        if (snapshots == null) {
            return null;
        }
        return snapshots.get(snapshotId).map(ItemSnapshot::plainName).orElse(null);
    }

    private static boolean isWellFormedCode(String id) {
        return id != null && id.length() >= 4 && id.chars().allMatch(Character::isLetterOrDigit);
    }

    /**
     * Builds the chat markup for one item link: a colour-coded, optionally clicky
     * {@code [Name ×n]} followed by a visible, typeable {@code (/itemview CODE)}
     * hint. The hint is the guaranteed path (it works by typing, on any input
     * device); the name-link is a bonus if the client dispatches command links.
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
        sb.append('<').append(normalizeColor(snapshot.accentColor().orElse(null))).append('>');
        sb.append('[').append(nameMarkup(snapshot));
        if (config.showQuantityInChat && snapshot.quantity() > 1) {
            sb.append(" ×").append(snapshot.quantity());
        }
        sb.append(']');
        // Close styling so the following chat text is unaffected.
        sb.append("</#>");
        if (config.underlineChatName) {
            sb.append("</u>");
        }
        if (link) {
            sb.append("</link>");
        }
        if (config.showViewCommandInChat) {
            sb.append(" <link:").append(command).append(">&8(&7").append(command)
                    .append("&8)</link>");
        }
        return sb.toString();
    }

    /** The name portion of a link: a client-translated segment or a safe literal. */
    private static String nameMarkup(ItemSnapshot snapshot) {
        var name = snapshot.view.displayName();
        if (name.isTranslated()) {
            return name.markup();
        }
        return ChatTokens.sanitizeInline(snapshot.plainName());
    }

    /** The normalized view-command label (no slash, lowercase). */
    private String viewCommandLabel() {
        String raw = config.viewCommand.trim().toLowerCase(Locale.ROOT);
        while (raw.startsWith("/")) {
            raw = raw.substring(1);
        }
        return raw.isBlank() ? "itemview" : raw;
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

    private Path viewConfigFile() {
        return core.paths().moduleExtraConfigFile("chat", "item-view.json");
    }

    private ItemLinkConfig loadConfig() {
        try {
            ItemLinkConfig loaded = Json.readFile(configFile(), ItemLinkConfig.class);
            if (loaded == null) {
                loaded = new ItemLinkConfig();
                Json.writeFile(configFile(), Json.toTree(loaded));
                core.log(Level.INFO, "Generated default modules/chat/item-links.json");
            }
            return loaded.normalized();
        } catch (Exception e) {
            core.log(Level.WARNING, "Failed to load item-links.json (keeping previous config): "
                    + e.getMessage());
            return config != null ? config : new ItemLinkConfig();
        }
    }

    private ItemViewConfig loadViewConfig() {
        try {
            ItemViewConfig loaded = Json.readFile(viewConfigFile(), ItemViewConfig.class);
            if (loaded == null) {
                loaded = new ItemViewConfig();
                Json.writeFile(viewConfigFile(), Json.toTree(loaded));
                core.log(Level.INFO, "Generated default modules/chat/item-view.json");
            }
            return loaded.normalized();
        } catch (Exception e) {
            core.log(Level.WARNING, "Failed to load item-view.json (keeping previous config): "
                    + e.getMessage());
            return viewConfig != null ? viewConfig : new ItemViewConfig().normalized();
        }
    }

    // ----- Page opening ---------------------------------------------------------

    void openDetails(PlayerRef player, ItemSnapshot snapshot) {
        if (!core.platform().openPage(player,
                new ItemDetailsPage(core, player, snapshot, viewConfig))) {
            core.getMessageService().send(player,
                    "&cCould not open the item details UI — see the server log.");
        }
    }

    void openRecent(PlayerRef player) {
        if (!core.platform().openPage(player,
                new RecentItemLinksPage(core, player, snapshots, viewConfig))) {
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
                    + normalizeColor(snapshot.accentColor().orElse(null)) + ">"
                    + ChatTokens.sanitizeInline(snapshot.plainName()) + "</#></link> &8(&7"
                    + command + "&8)");
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
            String argument = sender.arg(0).orElse("latest").trim().toLowerCase(Locale.ROOT);
            Optional<ItemSnapshot> target;
            if (argument.isBlank() || "latest".equals(argument) || "last".equals(argument)) {
                target = snapshots.latest(player.getUuid());
            } else if (argument.chars().allMatch(Character::isDigit)) {
                target = snapshots.recentAt(player.getUuid(), Integer.parseInt(argument));
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
