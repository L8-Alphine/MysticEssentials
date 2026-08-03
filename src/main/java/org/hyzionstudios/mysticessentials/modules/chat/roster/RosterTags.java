package org.hyzionstudios.mysticessentials.modules.chat.roster;

import org.hyzionstudios.mysticessentials.modules.chat.ChatConfig;

/**
 * Resolves the primary and secondary channel tags for a member from the configured
 * {@link ChatConfig.Roster} tag set (design bible §4).
 *
 * <p>When a player matches several roles the highest-priority tag becomes the
 * primary tag (default order {@code OWNER > CH MOD > STAFF > MEMBER}). A single
 * smaller secondary tag may then advertise a distinct staff role, e.g. an owner
 * who is also staff renders {@code [OWNER] Name [STAFF]} (§4.2), controlled by
 * {@link ChatConfig.Roster#allowSecondaryTags}.</p>
 */
public final class RosterTags {

    private RosterTags() {
    }

    /** The tag text + colour to show as the row's primary badge. */
    public static ChatConfig.Tag primary(ChatConfig.Roster config, ChannelMemberRole role, boolean staff) {
        ChatConfig.Tag roleTag = forRole(config, role);
        ChatConfig.Tag staffTag = config.staff;
        // Only staff, no channel authority → the staff tag is the primary tag.
        if (role == ChannelMemberRole.MEMBER && staff) {
            return higherPriority(roleTag, staffTag);
        }
        return roleTag;
    }

    /**
     * The optional secondary tag, or {@code null}. Present only when secondary tags
     * are enabled and the member is staff <em>while also</em> holding a higher channel
     * authority (owner/moderator) — so the staff badge is not lost behind the primary.
     */
    public static ChatConfig.Tag secondary(ChatConfig.Roster config, ChannelMemberRole role, boolean staff) {
        if (!config.allowSecondaryTags || config.maximumSecondaryTags <= 0) {
            return null;
        }
        if (staff && (role == ChannelMemberRole.OWNER || role == ChannelMemberRole.CHANNEL_MODERATOR)) {
            return config.staff;
        }
        return null;
    }

    private static ChatConfig.Tag forRole(ChatConfig.Roster config, ChannelMemberRole role) {
        return switch (role) {
            case OWNER -> config.owner;
            case CHANNEL_MODERATOR -> config.channelModerator;
            case MEMBER -> config.member;
        };
    }

    private static ChatConfig.Tag higherPriority(ChatConfig.Tag a, ChatConfig.Tag b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.priority > a.priority ? b : a;
    }

    /** Non-null display text for a tag, tolerating a missing/blank config value. */
    public static String text(ChatConfig.Tag tag, String fallback) {
        return tag == null || tag.text == null || tag.text.isBlank() ? fallback : tag.text;
    }

    /** Non-null {@code #RRGGBB} colour for a tag, tolerating a missing/blank config value. */
    public static String color(ChatConfig.Tag tag) {
        if (tag == null || tag.color == null || !tag.color.matches("#[0-9a-fA-F]{6}")) {
            return "#7a9cc6";
        }
        return tag.color;
    }
}
