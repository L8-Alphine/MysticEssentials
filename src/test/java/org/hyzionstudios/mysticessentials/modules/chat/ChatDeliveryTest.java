package org.hyzionstudios.mysticessentials.modules.chat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import org.hyzionstudios.mysticessentials.api.chat.ChatDeliveryResult;
import org.hyzionstudios.mysticessentials.api.chat.ChatDeliveryResult.Status;
import org.hyzionstudios.mysticessentials.api.chat.ChatMute;
import org.hyzionstudios.mysticessentials.core.integration.ModerationBridge;

/**
 * Dependency-free checks of {@code ChatService.deliver}'s rules: a muted sender is
 * refused, a shadow-muted one is shown only their own line, ignoring, offline and
 * policy-blocked recipients are skipped, and MysticModeration's mutes are read the way
 * its own chat gate reads them.
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
        ChatDelivery.Plan plan = ChatDelivery.plan(OFFLINE, List.of(MEMBER), null, false, IS_ONLINE,
                IGNORES_SENDER);
        require(plan.status() == Status.SENDER_OFFLINE && plan.recipients().isEmpty(),
                "a sender who is not on this server was delivered for: " + plan);
        require(ChatDelivery.plan(null, List.of(MEMBER), null, false, IS_ONLINE, IGNORES_SENDER).status()
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

    private static ChatDelivery.Plan plan(List<UUID> recipients, ChatMute mute, boolean empty) {
        return ChatDelivery.plan(SENDER, recipients, mute, empty, IS_ONLINE, IGNORES_SENDER);
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
