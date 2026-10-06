package org.hyzionstudios.mysticessentials.modules.chat;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import org.hyzionstudios.mysticessentials.api.chat.ChatDeliveryResult;
import org.hyzionstudios.mysticessentials.api.chat.ChatDeliveryResult.Status;
import org.hyzionstudios.mysticessentials.api.chat.ChatMute;
import org.hyzionstudios.mysticessentials.api.event.ChatDeliveredEvent;
import org.hyzionstudios.mysticessentials.core.integration.ModerationBridge;
import org.hyzionstudios.mysticessentials.core.integration.ModerationBridge.GuardVerdict;
import org.hyzionstudios.mysticessentials.core.notification.NotificationPreferences;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.chat.mention.MentionConfig;
import org.hyzionstudios.mysticessentials.modules.chat.mention.MentionSubModule;

/**
 * Dependency-free checks of {@code ChatService.deliver}'s rules and of the ignore list
 * shared with public chat: a muted sender is refused, a shadow-muted one is shown only
 * their own line, a chat guard or tutorial refusal blocks the line, ignoring, offline and
 * policy-blocked recipients are skipped, MysticModeration's mutes and chat guard verdicts
 * are read the way its own chat gates read them, {@code /ignore} edits the list (kept by
 * UUID, older name entries migrated), the list refuses private messages, and the
 * mention rules follow their config switches.
 */
public final class ChatDeliveryTest {

    private static final UUID SENDER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID IGNORER = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID OFFLINE = UUID.fromString("00000000-0000-4000-8000-000000000004");

    private static final Set<UUID> ONLINE = Set.of(SENDER, MEMBER, IGNORER);
    private static final Predicate<UUID> IS_ONLINE = ONLINE::contains;
    private static final BiPredicate<UUID, UUID> IGNORES_SENDER =
            (recipient, sender) -> recipient.equals(IGNORER) && sender.equals(SENDER);
    private static final ChatMute MUTE = new ChatMute(ChatMute.Scope.SERVER, "spam", null, false);
    private static final ChatMute SHADOW = new ChatMute(ChatMute.Scope.SERVER, "spam", null, true);

    private ChatDeliveryTest() {
    }

    public static void main(String[] args) throws Exception {
        reachesOnlineRecipientsAndTheSender();
        skipsIgnoringAndOfflineRecipients();
        mutedSenderIsRefused();
        shadowMutedSenderSeesOnlyTheirOwnLine();
        offlineSenderIsRefused();
        emptyMessageIsRefused();
        senderGetsTheLineEvenWhenNotListed();
        moderationMutesAreReadLikeItsChatGate();
        resultsNeverCarryNulls();
        blockedLinesAreRefusedAfterMutes();
        publicChatDropsTargetsWhoIgnoreTheSender();
        muteReasonsFallBackToADefault();
        chatGuardVerdictsAreReadLikeItsChatGate();
        ignoreCommandEditsTheList();
        ignoreListIsKeptByUuidAndMigratesNames();
        ignoredSendersCannotPrivateMessage();
        mentionRulesFollowTheirSwitches();
        deliveredEventsKeepTheirRecipients();
    }

    private static void reachesOnlineRecipientsAndTheSender() {
        ChatDelivery.Plan plan = plan(List.of(SENDER, MEMBER), null, false);
        require(plan.status() == Status.DELIVERED, "a plain line was not delivered: " + plan);
        require(plan.recipients().equals(List.of(MEMBER, SENDER)), "wrong recipients: " + plan.recipients());
        require(plan.skipped() == 0, "nobody should have been skipped: " + plan);
    }

    private static void skipsIgnoringAndOfflineRecipients() {
        ChatDelivery.Plan plan = plan(Arrays.asList(SENDER, MEMBER, IGNORER, OFFLINE, null, MEMBER), null, false);
        require(plan.status() == Status.DELIVERED, "the line was not delivered: " + plan);
        require(plan.recipients().equals(List.of(MEMBER, SENDER)),
                "an ignoring, offline, null or duplicate recipient got the line: " + plan.recipients());
        require(plan.skipped() == 2, "the ignoring and the offline recipient were not counted: " + plan);
    }

    private static void mutedSenderIsRefused() {
        ChatDelivery.Plan plan = plan(List.of(SENDER, MEMBER, IGNORER), MUTE, false);
        require(plan.status() == Status.MUTED, "a muted sender was not refused: " + plan);
        require(plan.recipients().isEmpty(), "a muted sender's line reached someone: " + plan.recipients());
        require(plan.skipped() == 2, "the refused recipients were not counted: " + plan);
        require(plan(List.of(MEMBER), MUTE, true).status() == Status.MUTED,
                "an empty line hid the mute from the sender");
    }

    private static void shadowMutedSenderSeesOnlyTheirOwnLine() {
        ChatDelivery.Plan plan = plan(List.of(SENDER, MEMBER), SHADOW, false);
        require(plan.status() == Status.SHADOW_MUTED, "a shadow mute was not applied: " + plan);
        require(plan.recipients().equals(List.of(SENDER)),
                "a shadow-muted line reached someone else: " + plan.recipients());
        require(new ChatDeliveryResult(Status.SHADOW_MUTED, 1, 1, "").isDelivered(),
                "a shadow-muted line must look delivered to the caller");
        require(!new ChatDeliveryResult(Status.MUTED, 0, 1, "spam").isDelivered(),
                "a muted line looked delivered");
    }

    private static void offlineSenderIsRefused() {
        ChatDelivery.Plan plan = ChatDelivery.plan(OFFLINE, List.of(MEMBER), null, false, false, IS_ONLINE,
                IGNORES_SENDER);
        require(plan.status() == Status.SENDER_OFFLINE && plan.recipients().isEmpty(),
                "a sender who is not on this server was delivered for: " + plan);
        require(ChatDelivery.plan(null, List.of(MEMBER), null, false, false, IS_ONLINE, IGNORES_SENDER).status()
                == Status.SENDER_OFFLINE, "a null sender was delivered for");
    }

    private static void emptyMessageIsRefused() {
        ChatDelivery.Plan plan = plan(List.of(MEMBER), null, true);
        require(plan.status() == Status.EMPTY && plan.recipients().isEmpty(), "an empty line was sent: " + plan);
        require(plan(List.of(MEMBER), SHADOW, true).status() == Status.EMPTY,
                "an empty shadow-muted line was sent");
    }

    private static void senderGetsTheLineEvenWhenNotListed() {
        ChatDelivery.Plan plan = plan(null, null, false);
        require(plan.status() == Status.DELIVERED && plan.recipients().equals(List.of(SENDER)),
                "the sender did not get their own line: " + plan);
    }

    private static void moderationMutesAreReadLikeItsChatGate() throws Exception {
        Instant until = Instant.parse("2026-10-07T12:00:00Z");
        ChatMute mute = ModerationBridge.chatMuteOf(new FakePunishment(FakeType.MUTE, "spam", until));
        require(mute.scope() == ChatMute.Scope.SERVER && !mute.shadow(), "a MUTE was not a server mute: " + mute);
        require("spam".equals(mute.reason()) && until.equals(mute.expiresAt()), "mute details lost: " + mute);

        ChatMute shadow = ModerationBridge.chatMuteOf(new FakePunishment(FakeType.SHADOW_MUTE, null, null));
        require(shadow.shadow(), "a SHADOW_MUTE was not a shadow mute");
        require("".equals(shadow.reason()) && shadow.expiresAt() == null, "a missing reason or expiry leaked: " + shadow);

        require(!ModerationBridge.chatMuteOf(new FakePunishment(FakeType.CHAT_BLOCK, "x", null)).shadow(),
                "a CHAT_BLOCK was read as a shadow mute");
    }

    private static void resultsNeverCarryNulls() {
        ChatDeliveryResult result = new ChatDeliveryResult(null, 0, 0, null);
        require(result.status() == Status.UNAVAILABLE && "".equals(result.reason()), "a result carried a null");
        require("".equals(new ChatMute(ChatMute.Scope.CHANNEL, null, null, false).reason()),
                "a mute carried a null reason");
    }

    private static void blockedLinesAreRefusedAfterMutes() {
        ChatDelivery.Plan plan = ChatDelivery.plan(SENDER, List.of(MEMBER), null, true, false, IS_ONLINE,
                IGNORES_SENDER);
        require(plan.status() == Status.BLOCKED && plan.recipients().isEmpty() && plan.skipped() == 1,
                "a blocked line was sent: " + plan);
        require(ChatDelivery.plan(SENDER, List.of(MEMBER), MUTE, true, false, IS_ONLINE, IGNORES_SENDER).status()
                == Status.MUTED, "a chat guard refusal hid the mute from the sender");
        require(ChatDelivery.plan(SENDER, List.of(MEMBER), SHADOW, true, false, IS_ONLINE, IGNORES_SENDER).status()
                == Status.BLOCKED, "a shadow-muted line skipped the chat guard");
        require(ChatDelivery.plan(SENDER, List.of(MEMBER), null, true, true, IS_ONLINE, IGNORES_SENDER).status()
                == Status.BLOCKED, "an empty line hid a refusal the sender must hear about");
        require(!new ChatDeliveryResult(Status.BLOCKED, 0, 1, "Chat is locked").isDelivered(),
                "a blocked line looked delivered");
    }

    private static void publicChatDropsTargetsWhoIgnoreTheSender() {
        List<UUID> kept = ChatDelivery.withoutIgnoring(Arrays.asList(SENDER, MEMBER, null, IGNORER),
                id -> id, SENDER, id -> IGNORES_SENDER.test(id, SENDER));
        require(kept.equals(List.of(SENDER, MEMBER)), "an ignoring target kept the line: " + kept);
        List<UUID> own = ChatDelivery.withoutIgnoring(List.of(SENDER), id -> id, SENDER, id -> true);
        require(own.equals(List.of(SENDER)), "the sender lost their own line");
    }

    private static void muteReasonsFallBackToADefault() {
        require("spam".equals(ChatDelivery.reasonOrDefault("spam", "none")), "a given reason was replaced");
        require("none".equals(ChatDelivery.reasonOrDefault("", "none"))
                && "none".equals(ChatDelivery.reasonOrDefault(null, "none"))
                && "none".equals(ChatDelivery.reasonOrDefault("  ", "none")), "a missing reason stayed empty");
    }

    private static void chatGuardVerdictsAreReadLikeItsChatGate() throws Exception {
        require(ModerationBridge.guardVerdictOf(new FakeCheck(FakeOutcome.ALLOW, null, null)) == GuardVerdict.ALLOW,
                "ALLOW was not allowed");
        GuardVerdict blocked = ModerationBridge.guardVerdictOf(
                new FakeCheck(FakeOutcome.BLOCK, "CHAT_LOCK", "&cChat is currently locked by staff."));
        require(blocked.outcome() == GuardVerdict.Outcome.BLOCK
                && "&cChat is currently locked by staff.".equals(blocked.text()),
                "BLOCK lost its feedback: " + blocked);
        GuardVerdict rewritten = ModerationBridge.guardVerdictOf(new FakeCheck(FakeOutcome.REWRITE, "CAPS", "hello"));
        require(rewritten.outcome() == GuardVerdict.Outcome.REWRITE && "hello".equals(rewritten.text()),
                "REWRITE lost its text: " + rewritten);
        require(ModerationBridge.guardVerdictOf(new FakeCheck(FakeOutcome.REWRITE, "CAPS", null))
                == GuardVerdict.ALLOW, "an empty rewrite would have sent nothing");
        try {
            ModerationBridge.guardVerdictOf(new FakeCheck(FakeOutcome.QUARANTINE, null, null));
            throw new AssertionError("an unknown chat guard outcome was let through");
        } catch (ReflectiveOperationException expected) {
            // Correct: an outcome the bridge does not know refuses the line.
        }
    }

    private static void ignoreCommandEditsTheList() {
        NotificationPreferences preferences = new NotificationPreferences();
        require(IgnoreSubModule.apply(preferences, SENDER, MEMBER, true, false) == IgnoreSubModule.Outcome.ADDED,
                "/ignore did not add a player");
        require(preferences.ignores(MEMBER, null) && preferences.ignoredIds().equals(List.of(MEMBER)),
                "the ignore list did not keep the UUID: " + preferences.ignoredIds());
        require(IgnoreSubModule.apply(preferences, SENDER, MEMBER, true, false) == IgnoreSubModule.Outcome.ALREADY,
                "a second /ignore added a duplicate");
        require(IgnoreSubModule.apply(preferences, SENDER, SENDER, true, false) == IgnoreSubModule.Outcome.SELF,
                "a player ignored themselves");
        require(IgnoreSubModule.apply(preferences, SENDER, IGNORER, true, true) == IgnoreSubModule.Outcome.EXEMPT
                && !preferences.ignores(IGNORER, null), "an exempt player was ignored");
        require(IgnoreSubModule.apply(preferences, SENDER, MEMBER, false, false) == IgnoreSubModule.Outcome.REMOVED
                && !preferences.ignores(MEMBER, null), "/unignore did not remove the player");
        require(IgnoreSubModule.apply(preferences, SENDER, MEMBER, false, false)
                == IgnoreSubModule.Outcome.NOT_IGNORED, "/unignore of a player not ignored reported a change");
    }

    private static void ignoreListIsKeptByUuidAndMigratesNames() {
        // A list stored before it was kept by UUID: names only.
        NotificationPreferences legacy = Json.gson()
                .fromJson("{\"blockedMentioners\":[\"alex\",\"ghost\"]}", NotificationPreferences.class)
                .normalized();
        require(legacy.ignores(null, "Alex") && legacy.ignoredCount() == 2,
                "an unresolved name entry stopped applying: " + legacy.pendingIgnoredNames());
        require(legacy.resolvePendingIgnore("ALEX", MEMBER), "a pending name did not resolve");
        require(legacy.ignores(MEMBER, "Alex2") && !legacy.ignores(null, "alex"),
                "a resolved entry still matched by name, or not by UUID after a rename");
        require(legacy.pendingIgnoredNames().equals(List.of("ghost")) && legacy.ignores(null, "ghost"),
                "an unresolved name was dropped instead of kept");
        require(!legacy.resolvePendingIgnore("alex", IGNORER), "a name resolved twice");

        NotificationPreferences reloaded = Json.gson()
                .fromJson(Json.toString(Json.gson().toJsonTree(legacy)), NotificationPreferences.class).normalized();
        require(reloaded.ignoredIds().equals(List.of(MEMBER)) && reloaded.pendingIgnoredNames().equals(List.of("ghost")),
                "the ignore list did not survive a save: " + reloaded.ignoredIds() + " " + reloaded.pendingIgnoredNames());
        require(reloaded.removePendingIgnore("Ghost") && reloaded.ignoredCount() == 1, "/unignore of a pending name failed");
    }

    private static void ignoredSendersCannotPrivateMessage() {
        NotificationPreferences target = new NotificationPreferences();
        target.setIgnored(SENDER, true);
        require(PrivateMessagingSubModule.refusedByIgnore(target, SENDER, "Steve", false),
                "an ignored player could send a private message");
        require(!PrivateMessagingSubModule.refusedByIgnore(target, SENDER, "Steve", true),
                "an exempt sender was refused");
        require(!PrivateMessagingSubModule.refusedByIgnore(target, MEMBER, "Alex", false), "a stranger was refused");
        NotificationPreferences legacy = Json.gson()
                .fromJson("{\"blockedMentioners\":[\"alex\"]}", NotificationPreferences.class).normalized();
        require(PrivateMessagingSubModule.refusedByIgnore(legacy, MEMBER, "Alex", false),
                "a not yet resolved name entry let a private message through");
        require(!PrivateMessagingSubModule.refusedByIgnore(target, null, "Server", false),
                "a console message was refused");
    }

    private static void mentionRulesFollowTheirSwitches() {
        MentionConfig.Rules rules = new MentionConfig.Rules();
        NotificationPreferences preferences = new NotificationPreferences();
        preferences.setIgnored(MEMBER, true);
        require(MentionSubModule.recipientRefuses(rules, preferences, MEMBER, "Alex"),
                "an ignored player could mention");
        require(!MentionSubModule.recipientRefuses(rules, preferences, SENDER, "Steve"), "a stranger was refused");
        rules.ignoredPlayersCanNotNotify = false;
        require(!MentionSubModule.recipientRefuses(rules, preferences, MEMBER, "Alex"),
                "ignoredPlayersCanNotNotify=false still refused an ignored player");
        preferences.doNotDisturb = true;
        require(MentionSubModule.recipientRefuses(rules, preferences, SENDER, "Steve"),
                "do-not-disturb stopped working");

        MentionConfig.Rules defaults = new MentionConfig.Rules();
        require(!MentionSubModule.senderMayNotify(defaults, true), "a muted player could mention");
        require(MentionSubModule.senderMayNotify(defaults, false), "an unmuted player could not mention");
        defaults.mutedPlayersCanNotNotify = false;
        require(MentionSubModule.senderMayNotify(defaults, true),
                "mutedPlayersCanNotNotify=false still silenced a muted player");
    }

    private static void deliveredEventsKeepTheirRecipients() {
        Set<UUID> reached = new LinkedHashSet<>(List.of(SENDER, MEMBER));
        ChatDeliveredEvent event = new ChatDeliveredEvent(SENDER, "Steve", "Steve", "Guild", "hi", reached, false);
        reached.clear();
        require(event.recipients().equals(Set.of(SENDER, MEMBER)), "the event shares the caller's set");
        require(new ChatDeliveredEvent(SENDER, "Steve", "Steve", "Guild", "hi", null, true).recipients().isEmpty(),
                "a null recipient set leaked");
    }

    private static ChatDelivery.Plan plan(List<UUID> recipients, ChatMute mute, boolean empty) {
        return ChatDelivery.plan(SENDER, recipients, mute, false, empty, IS_ONLINE, IGNORES_SENDER);
    }

    /** MysticModeration's {@code ChatGuardService.ChatCheck.Outcome}, plus one it does not have. */
    public enum FakeOutcome {
        ALLOW, BLOCK, REWRITE, QUARANTINE
    }

    /** MysticModeration's {@code ChatGuardService.ChatCheck} record, in the part the bridge reads. */
    public record FakeCheck(FakeOutcome outcome, String violation, String detail) {
    }

    /** MysticModeration's {@code PunishmentType}, in the part the bridge reads. */
    public enum FakeType {
        MUTE, SHADOW_MUTE, CHAT_BLOCK
    }

    /** MysticModeration's {@code Punishment} record, in the part the bridge reads. */
    public record FakePunishment(FakeType type, String reason, Instant expiresAt) {
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
