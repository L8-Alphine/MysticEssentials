package org.hyzionstudios.mysticessentials.modules.chat.roster;

/**
 * A member's authority <em>inside a single channel</em> (design bible §18.3).
 *
 * <p>Deliberately does <strong>not</strong> include server staff: staff status is a
 * server-wide classification resolved dynamically from the permission provider so
 * rank changes take effect immediately, and is carried alongside this role on
 * {@link ChannelMemberView}. Keeping the two axes separate is a core design
 * principle (§2.1): a speaking player is not a moderator, and a staff member is
 * not automatically a channel authority.</p>
 */
public enum ChannelMemberRole {

    /** Player-owner of a temporary or private player-owned channel. */
    OWNER,

    /** Appointed to help manage this specific channel. */
    CHANNEL_MODERATOR,

    /** A normal channel member. */
    MEMBER
}
