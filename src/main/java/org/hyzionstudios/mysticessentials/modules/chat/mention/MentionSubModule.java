package org.hyzionstudios.mysticessentials.modules.chat.mention;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.mention.MentionScopeProvider;
import org.hyzionstudios.mysticessentials.api.notification.Notification;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAudience;
import org.hyzionstudios.mysticessentials.api.notification.NotificationCategory;
import org.hyzionstudios.mysticessentials.api.notification.NotificationPriority;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.notification.NotificationPreferences;
import org.hyzionstudios.mysticessentials.core.notification.NotificationServiceImpl;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.chat.ChatTokens;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Player mentions: {@code @PlayerName} in chat highlights the line for its
 * target and nudges them with a sound, a title, and a history entry.
 *
 * <p>Detection happens <b>after</b> item links are tokenized, which is what
 * keeps mentions out of item metadata for free — by that point a shared item is
 * an opaque token with no readable text for an {@code @} to hide in.</p>
 *
 * <p>Everything a mention does to a recipient is rate-limited and preference-
 * gated. The limits are per-sender, per-sender-and-target, and per-recipient, so
 * one persistent person cannot pin somebody's screen, and the recipient's own
 * settings — do-not-disturb, blocked players, mention scope — always win. The
 * only actor that can override any of this is the server operator, through
 * config.</p>
 */
public final class MentionSubModule {

    private final MysticCore core;
    private MentionConfig config = new MentionConfig();

    /** Last mention timestamp per sender, for the global cooldown. */
    private final Map<UUID, Long> lastMentionBySender = new ConcurrentHashMap<>();
    /** Last mention timestamp per sender-target pair. */
    private final Map<String, Long> lastMentionByPair = new ConcurrentHashMap<>();
    /** Last mention-sound timestamp per recipient. */
    private final Map<UUID, Long> lastSoundByRecipient = new ConcurrentHashMap<>();
    /** Rolling per-minute mention timestamps per sender. */
    private final Map<UUID, Deque<Long>> recentBySender = new ConcurrentHashMap<>();
    /** Last mass-mention timestamp per sender. */
    private final Map<UUID, Long> lastMassBySender = new ConcurrentHashMap<>();

    /**
     * Available "who may mention you" options, by id. Seeded with the two this
     * mod can enforce alone; anything relationship-based (friends, guild, party)
     * is contributed by the mod that owns that relationship.
     */
    private final Map<String, MentionScopeProvider> scopes = new ConcurrentHashMap<>();

    /**
     * The outcome of scanning one message.
     *
     * @param content    the message with mentions replaced by tokens
     * @param mentioned  the players who should be notified
     * @param perViewer  whether the line must be rendered per recipient
     */
    public record Result(String content, Set<UUID> mentioned, boolean perViewer) {

        static Result unchanged(String content) {
            return new Result(content, Set.of(), false);
        }

        public boolean hasMentions() {
            return !mentioned.isEmpty();
        }
    }

    public MentionSubModule(MysticCore core) {
        this.core = core;
    }

    // ----- Lifecycle --------------------------------------------------------------

    public void enable(Consumer<MysticCommand> commandRegistrar) {
        config = loadConfig();
        registerBuiltInScopes();
        commandRegistrar.accept(new MentionsCommand());
    }

    /**
     * The only two scopes this mod can honour without help. Everything else —
     * "Friends Only", "Guild Members" — needs a system this mod does not model,
     * so it is registered by the mod that does. Shipping those options here with
     * nothing behind them would give players a setting that silently does
     * nothing.
     */
    private void registerBuiltInScopes() {
        registerScope(new BuiltInScope(NotificationPreferences.SCOPE_EVERYONE, "Everyone", 0,
                (sender, target) -> true));
        registerScope(new BuiltInScope(NotificationPreferences.SCOPE_NOBODY, "Nobody", 1000,
                (sender, target) -> false));
    }

    /** Registers (or replaces) a mention scope contributed by another mod. */
    public void registerScope(MentionScopeProvider provider) {
        if (provider == null) {
            return;
        }
        String id = safeScopeId(provider);
        if (id.isEmpty()) {
            return;
        }
        scopes.put(id, provider);
        core.log(Level.INFO, "[chat] mentions: registered scope '" + id + "'.");
    }

    public boolean unregisterScope(String scopeId) {
        return scopeId != null && scopes.remove(scopeId.trim().toLowerCase(Locale.ROOT)) != null;
    }

    /** Currently selectable scopes, in display order, excluding unavailable ones. */
    public List<MentionScopeProvider> availableScopes() {
        List<MentionScopeProvider> out = new ArrayList<>();
        for (MentionScopeProvider provider : scopes.values()) {
            if (isAvailable(provider)) {
                out.add(provider);
            }
        }
        out.sort(java.util.Comparator
                .comparingInt(MentionSubModule::safeSortOrder)
                .thenComparing(MentionSubModule::safeDisplayName));
        return out;
    }

    /** A registered scope by id, or empty when nothing currently implements it. */
    public java.util.Optional<MentionScopeProvider> scope(String scopeId) {
        return scopeId == null
                ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(scopes.get(scopeId.trim().toLowerCase(Locale.ROOT)));
    }

    /** A built-in scope with a fixed answer; no external system behind it. */
    private record BuiltInScope(String id, String displayName, int sortOrder,
            java.util.function.BiPredicate<UUID, UUID> rule) implements MentionScopeProvider {

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public int getSortOrder() {
            return sortOrder;
        }

        @Override
        public boolean allows(UUID sender, UUID target) {
            return rule.test(sender, target);
        }
    }

    public void reload() {
        config = loadConfig();
    }

    public void invalidate(UUID player) {
        lastMentionBySender.remove(player);
        lastSoundByRecipient.remove(player);
        recentBySender.remove(player);
        lastMassBySender.remove(player);
        lastMentionByPair.keySet().removeIf(key -> key.contains(player.toString()));
    }

    public boolean isEnabled() {
        return config.enabled;
    }

    public MentionConfig config() {
        return config;
    }

    // ----- Detection ----------------------------------------------------------------

    /**
     * Scans {@code message} for mentions, replacing each with a token and
     * returning the players to notify.
     *
     * <p>Notifications are <i>not</i> sent here — the message has not been
     * delivered yet, and a recipient should not hear the ping before they can
     * read the line. Call {@link #notifyMentioned} once delivery has happened.</p>
     */
    public Result process(PlayerRef sender, String message, String channelName,
            List<PlayerRef> recipients) {
        if (!config.enabled || message == null || sender == null) {
            return Result.unchanged(message);
        }
        if (!hasPermission(sender, Permissions.CHAT_MENTION)) {
            return Result.unchanged(message);
        }
        if (!message.contains(config.prefix)) {
            return Result.unchanged(message);
        }

        Map<String, PlayerRef> candidates = candidateNames(recipients, sender);
        List<String> massKeywords = massKeywords();
        int limit = effectiveMessageLimit(sender);

        List<MentionMatcher.Match> matches = MentionMatcher.find(
                message, config.prefix, config.matching.caseSensitive,
                typed -> {
                    PlayerRef found = candidates.get(typed.toLowerCase(Locale.ROOT));
                    return found == null ? null : found.getUsername();
                },
                massKeywords, limit);

        if (matches.isEmpty()) {
            return Result.unchanged(message);
        }
        if (!withinSenderBudget(sender)) {
            // Over budget: the message still sends, it just stops pinging. Silently
            // dropping the whole line would be a far worse failure mode.
            return Result.unchanged(message);
        }

        StringBuilder out = new StringBuilder(message.length() + 32);
        Set<UUID> mentioned = new LinkedHashSet<>();
        int cursor = 0;

        for (MentionMatcher.Match match : matches) {
            out.append(message, cursor, match.start());
            cursor = match.end();

            if (match.mass()) {
                out.append(handleMass(sender, match, mentioned, recipients));
                continue;
            }
            PlayerRef target = candidates.get(match.resolved().toLowerCase(Locale.ROOT));
            if (target == null || !mayMention(sender, target)) {
                // Not deliverable — leave the text exactly as typed rather than
                // silently rewriting somebody's sentence.
                out.append(config.prefix).append(match.typed());
                continue;
            }
            mentioned.add(target.getUuid());
            recordMention(sender, target);
            out.append(ChatTokens.mentionToken(target.getUsername()));
        }
        out.append(message.substring(cursor));

        boolean perViewer = config.notifications.perViewerRendering
                && config.notifications.chatHighlight
                && !mentioned.isEmpty();
        return new Result(out.toString(), mentioned, perViewer);
    }

    /**
     * Expands one mention token for a specific viewer. The mentioned player sees
     * the highlighted form; everybody else sees the neutral one.
     */
    public String renderMention(String name, UUID viewer, Set<UUID> mentioned) {
        // Compare against the viewer directly rather than looking the name up:
        // it avoids a per-token player lookup, and it is null-safe against the
        // immutable empty set the caller may pass (Set.of().contains(null) throws).
        // A mass keyword highlights for everyone it reached — @everyone is aimed
        // at each of them individually, even though it names none of them.
        boolean addressed = viewer != null && mentioned != null && mentioned.contains(viewer)
                && (massKeywords().contains(name.toLowerCase(Locale.ROOT))
                        || name.equalsIgnoreCase(usernameOf(viewer)));
        boolean highlight = config.notifications.chatHighlight && addressed;
        String template = highlight
                ? config.notifications.highlightFormat
                : config.notifications.bystanderFormat;
        return template
                .replace("{prefix}", config.prefix)
                .replace("{name}", ChatTokens.sanitizeInline(name));
    }

    /** The neutral rendering, for the console echo and any single-line delivery. */
    public String renderMentionNeutral(String name) {
        return config.notifications.bystanderFormat
                .replace("{prefix}", config.prefix)
                .replace("{name}", ChatTokens.sanitizeInline(name));
    }

    // ----- Notification ----------------------------------------------------------------

    /**
     * Nudges every mentioned player, after the message itself has been delivered.
     *
     * <p>Runs through the shared notification engine rather than sending a title
     * and a sound directly, so mentions obey the same preferences, cooldowns, and
     * history rules as every other notice on the server.</p>
     */
    public void notifyMentioned(PlayerRef sender, Set<UUID> mentioned, String channelName,
            String preview) {
        NotificationServiceImpl notifications = core.notifications();
        if (notifications == null || mentioned.isEmpty()) {
            return;
        }
        // A staff member who was allowed through the recipient's settings must
        // also reach them at the notification layer, or do-not-disturb would
        // swallow the ping one step later and the bypass would be cosmetic.
        boolean staffOverride = config.rules.staffBypassPlayerSettings
                && hasPermission(sender, Permissions.CHAT_MENTION_BYPASS_SETTINGS);

        String channel = channelName == null || channelName.isBlank() ? "chat" : channelName;
        for (UUID target : mentioned) {
            NotificationPreferences preferences = notifications.preferences(target);
            boolean sound = config.notifications.soundEnabled
                    && (staffOverride || preferences.mentionSound)
                    && soundAllowed(target);

            notifications.send(Notification.builder()
                    .category(NotificationCategory.MENTION)
                    .priority(NotificationPriority.IMPORTANT)
                    .title(config.notifications.titleEnabled
                            && (staffOverride || preferences.mentionTitle)
                            ? config.notifications.title : null)
                    .subtitle(config.notifications.subtitleEnabled
                            && (staffOverride || preferences.mentionTitle)
                            ? placeholders(config.notifications.subtitle, sender, channel) : null)
                    .message(preview)
                    .sound(sound ? config.notifications.sound : null)
                    // The chat line already carries the mention; a second copy in
                    // chat would just be noise.
                    .showInChat(false)
                    .showAsTitle(config.notifications.titleEnabled
                            && (staffOverride || preferences.mentionTitle))
                    .showAsActionBar(config.notifications.actionbarEnabled
                            && (staffOverride || preferences.mentionActionBar))
                    .bypassPlayerPreferences(staffOverride)
                    .storeInHistory(true)
                    .action(org.hyzionstudios.mysticessentials.api.notification.NotificationAction
                            .channel(channel))
                    .source("mysticessentials:chat")
                    .build(),
                    NotificationAudience.player(target));
        }
    }

    private String placeholders(String template, PlayerRef sender, String channel) {
        if (template == null) {
            return null;
        }
        return template
                .replace("{sender}", sender == null ? "someone" : sender.getUsername())
                .replace("{channel}", channel);
    }

    // ----- Eligibility and limits -------------------------------------------------------

    /**
     * Candidate targets, keyed by lowercase name. Built from the message's actual
     * recipients so a mention can only reach somebody who was going to see the
     * line anyway — mentioning a player in a channel they are not in must not
     * ping them.
     */
    private Map<String, PlayerRef> candidateNames(List<PlayerRef> recipients, PlayerRef sender) {
        Map<String, PlayerRef> out = new LinkedHashMap<>();
        Iterable<PlayerRef> source = recipients == null || recipients.isEmpty()
                ? core.platform().onlinePlayers()
                : recipients;
        // Staff who may bypass player settings can also reach a vanished player;
        // for everybody else the vanish filter stands, since it doubles as the
        // guard against using mentions to probe who is secretly online.
        boolean seesVanished = config.rules.vanishedPlayersReceiveMentions
                || (config.rules.staffBypassPlayerSettings
                        && hasPermission(sender, Permissions.CHAT_MENTION_BYPASS_SETTINGS));
        for (PlayerRef player : source) {
            if (player == null) {
                continue;
            }
            if (!seesVanished && isVanished(player)) {
                continue;
            }
            out.put(player.getUsername().toLowerCase(Locale.ROOT), player);
        }
        return out;
    }

    private boolean isVanished(PlayerRef player) {
        try {
            return core.vanish().isVanished(player.getUuid());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether {@code sender} may currently mention {@code target}.
     *
     * <p>Staff holding {@link Permissions#CHAT_MENTION_BYPASS_SETTINGS} skip the
     * recipient-side gates — scope, block list, do-not-disturb, and the
     * same-target cooldown. That exists so a moderator can actually reach someone
     * who has muted the world, which is exactly when reaching them matters. It
     * deliberately does <b>not</b> bypass the self-mention rule (nothing to
     * enforce) and is a separate node from the sender-side cooldown bypass, so
     * "can ping through settings" and "can ping rapidly" are granted
     * independently.</p>
     */
    private boolean mayMention(PlayerRef sender, PlayerRef target) {
        if (target.getUuid().equals(sender.getUuid()) && !config.rules.allowSelfMention) {
            return false;
        }
        boolean staffBypass = config.rules.staffBypassPlayerSettings
                && hasPermission(sender, Permissions.CHAT_MENTION_BYPASS_SETTINGS);
        if (staffBypass) {
            return true;
        }

        NotificationServiceImpl notifications = core.notifications();
        if (notifications != null) {
            NotificationPreferences preferences = notifications.preferences(target.getUuid());
            if (preferences.blocks(sender.getUsername()) || preferences.doNotDisturb) {
                return false;
            }
            if (!scopeAllows(preferences, sender.getUuid(), target.getUuid())) {
                return false;
            }
        }
        long now = System.currentTimeMillis();
        Long lastPair = lastMentionByPair.get(pairKey(sender.getUuid(), target.getUuid()));
        return lastPair == null
                || now - lastPair >= config.limits.sameTargetCooldownSeconds * 1000L;
    }

    /**
     * Applies the recipient's chosen scope.
     *
     * <p>A stored scope whose provider is not currently registered falls back to
     * allowing the mention rather than blocking it. The reasoning: the missing
     * provider is a server-side condition the player had no part in, and silently
     * turning "Guild Members Only" into "Nobody" because a mod failed to load
     * would look like a bug in mentions. {@code nobody} is built in and therefore
     * always enforceable, so the one choice that unambiguously means "leave me
     * alone" never degrades.</p>
     */
    private boolean scopeAllows(NotificationPreferences preferences, UUID sender, UUID target) {
        MentionScopeProvider provider = scopes.get(preferences.scopeId());
        if (provider == null) {
            return !preferences.blocksAllMentions();
        }
        try {
            return provider.allows(sender, target);
        } catch (Throwable t) {
            // Log defensively: a catch clause that can itself throw turns a
            // contained third-party fault into an uncontained one on the chat path.
            try {
                core.log(Level.WARNING, "[chat] mentions: scope '" + preferences.scopeId()
                        + "' threw; treating the mention as blocked: " + t);
            } catch (Throwable ignored) {
                // Nothing useful left to do; the mention is still blocked below.
            }
            return false;
        }
    }

    /** Whether {@code sender} is inside their global and per-minute mention budget. */
    private boolean withinSenderBudget(PlayerRef sender) {
        if (hasPermission(sender, Permissions.CHAT_MENTION_BYPASS_COOLDOWN)) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = lastMentionBySender.get(sender.getUuid());
        if (last != null && now - last < config.limits.senderGlobalCooldownSeconds * 1000L) {
            return false;
        }
        Deque<Long> recent = recentBySender.computeIfAbsent(sender.getUuid(),
                uuid -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() > 60_000L) {
                recent.removeFirst();
            }
            return recent.size() < config.limits.maxMentionsPerMinute;
        }
    }

    private void recordMention(PlayerRef sender, PlayerRef target) {
        long now = System.currentTimeMillis();
        lastMentionBySender.put(sender.getUuid(), now);
        lastMentionByPair.put(pairKey(sender.getUuid(), target.getUuid()), now);
        Deque<Long> recent = recentBySender.computeIfAbsent(sender.getUuid(),
                uuid -> new ArrayDeque<>());
        synchronized (recent) {
            recent.addLast(now);
        }
    }

    /** Whether a recipient's mention sound is off cooldown, consuming it if so. */
    private boolean soundAllowed(UUID recipient) {
        long now = System.currentTimeMillis();
        Long last = lastSoundByRecipient.get(recipient);
        if (last != null && now - last < config.limits.recipientSoundCooldownSeconds * 1000L) {
            return false;
        }
        lastSoundByRecipient.put(recipient, now);
        return true;
    }

    private int effectiveMessageLimit(PlayerRef sender) {
        if (hasPermission(sender, Permissions.CHAT_MENTION_MULTIPLE)) {
            return config.limits.maxMentionsPerMessage;
        }
        return 1;
    }

    private static String pairKey(UUID sender, UUID target) {
        return sender + ">" + target;
    }

    private static boolean hasPermission(PlayerRef player, String permission) {
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    // ----- Mass mentions ------------------------------------------------------------------

    private List<String> massKeywords() {
        if (!config.massMentions.enabled) {
            return List.of();
        }
        return List.of(
                config.massMentions.everyoneKeyword.toLowerCase(Locale.ROOT),
                config.massMentions.onlineKeyword.toLowerCase(Locale.ROOT),
                config.massMentions.staffKeyword.toLowerCase(Locale.ROOT),
                config.massMentions.channelKeyword.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves {@code @everyone} and friends. Each keyword needs its own
     * permission and shares a much longer cooldown, because the cost of getting
     * this wrong scales with the player count.
     */
    private String handleMass(PlayerRef sender, MentionMatcher.Match match, Set<UUID> mentioned,
            List<PlayerRef> recipients) {
        String keyword = match.resolved();
        String permission = massPermission(keyword);
        String literal = config.prefix + match.typed();

        if (!hasPermission(sender, permission) || !massCooldownReady(sender)) {
            return literal;
        }
        List<PlayerRef> targets = massTargets(keyword, recipients);
        if (targets.isEmpty()) {
            return literal;
        }
        lastMassBySender.put(sender.getUuid(), System.currentTimeMillis());
        for (PlayerRef target : targets) {
            if (!target.getUuid().equals(sender.getUuid())) {
                mentioned.add(target.getUuid());
            }
        }
        return ChatTokens.mentionToken(match.typed());
    }

    private String massPermission(String keyword) {
        MentionConfig.MassMentions mass = config.massMentions;
        if (keyword.equals(mass.staffKeyword.toLowerCase(Locale.ROOT))) {
            return Permissions.CHAT_MENTION_STAFF;
        }
        if (keyword.equals(mass.channelKeyword.toLowerCase(Locale.ROOT))) {
            return Permissions.CHAT_MENTION_CHANNEL;
        }
        return Permissions.CHAT_MENTION_EVERYONE;
    }

    private boolean massCooldownReady(PlayerRef sender) {
        if (hasPermission(sender, Permissions.CHAT_MENTION_BYPASS_COOLDOWN)) {
            return true;
        }
        Long last = lastMassBySender.get(sender.getUuid());
        return last == null
                || System.currentTimeMillis() - last >= config.massMentions.cooldownSeconds * 1000L;
    }

    private List<PlayerRef> massTargets(String keyword, List<PlayerRef> recipients) {
        MentionConfig.MassMentions mass = config.massMentions;
        if (keyword.equals(mass.staffKeyword.toLowerCase(Locale.ROOT))) {
            List<PlayerRef> staff = new ArrayList<>();
            for (PlayerRef player : core.platform().onlinePlayers()) {
                if (player != null && player.hasPermission(Permissions.CHAT_MENTION_STAFF)) {
                    staff.add(player);
                }
            }
            return staff;
        }
        if (keyword.equals(mass.channelKeyword.toLowerCase(Locale.ROOT))) {
            return recipients == null ? List.of() : recipients;
        }
        return new ArrayList<>(core.platform().onlinePlayers());
    }

    private String usernameOf(UUID player) {
        return core.platform().findPlayer(player).map(PlayerRef::getUsername).orElse("");
    }

    // ----- Defensive scope accessors -------------------------------------------------
    // A third-party scope provider is untrusted code on the chat path: a throw
    // from any of these costs that provider's option, never the message.

    private static String safeScopeId(MentionScopeProvider provider) {
        try {
            String id = provider.getId();
            return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safeDisplayName(MentionScopeProvider provider) {
        try {
            String name = provider.getDisplayName();
            return name == null || name.isBlank() ? safeScopeId(provider) : name;
        } catch (Throwable t) {
            return safeScopeId(provider);
        }
    }

    private static int safeSortOrder(MentionScopeProvider provider) {
        try {
            return provider.getSortOrder();
        } catch (Throwable t) {
            return 100;
        }
    }

    private static boolean isAvailable(MentionScopeProvider provider) {
        try {
            return provider.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The label to show for a scope id, for the settings UI. */
    static String displayNameOf(MentionScopeProvider provider) {
        return safeDisplayName(provider);
    }

    static String idOf(MentionScopeProvider provider) {
        return safeScopeId(provider);
    }

    // ----- Config ---------------------------------------------------------------------------

    private Path configFile() {
        return core.paths().moduleExtraConfigFile("chat", "mentions.json");
    }

    private MentionConfig loadConfig() {
        try {
            MentionConfig loaded = Json.readFile(configFile(), MentionConfig.class);
            if (loaded == null) {
                loaded = new MentionConfig();
                Json.writeFile(configFile(), Json.toTree(loaded));
                core.log(Level.INFO, "Generated default modules/chat/mentions.json");
            }
            return loaded.normalized();
        } catch (Exception e) {
            core.log(Level.WARNING, "Failed to load mentions.json (keeping previous config): "
                    + e.getMessage());
            return config != null ? config : new MentionConfig();
        }
    }

    // ----- Command ----------------------------------------------------------------------------

    /** {@code /mentions} — opens the per-player mention settings panel. */
    private final class MentionsCommand extends MysticCommand {
        MentionsCommand() {
            super(MentionSubModule.this.core, "mentions",
                    "Configure who may mention you and how you are notified.");
            addAliases("mentionsettings");
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
            NotificationServiceImpl notifications = core.notifications();
            if (notifications == null) {
                sender.reply("&cNotifications are unavailable on this server.");
                return;
            }
            if (!core.platform().openPage(player,
                    new MentionSettingsPage(core, player, notifications,
                            MentionSubModule.this, config))) {
                sender.reply("&cCould not open the mention settings UI — see the server log.");
            }
        }
    }
}
