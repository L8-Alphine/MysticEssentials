package org.hyzionstudios.mysticessentials.modules.chat.roster;

import java.util.UUID;

/**
 * An immutable read model for one member row in the roster UI (design bible §5.4,
 * §6). Built per-request from live channel state; never persisted.
 *
 * <p>The three classifications the design keeps separate (§2.1) are all present:
 * {@link #role() authority}, {@link #participation()} and the {@link #staff} flag
 * (server-wide, resolved dynamically). {@link #primaryTag()} is the highest-priority
 * tag to display and {@link #secondaryTag()} an optional smaller staff indicator
 * (§4.2). {@link #tagColor()} is a {@code #RRGGBB} accent for the row swatch.</p>
 */
public record ChannelMemberView(
        UUID playerId,
        String name,
        ChannelMemberRole role,
        boolean staff,
        String serverRank,
        ChannelParticipation participation,
        ChannelActivity activity,
        String primaryTag,
        String secondaryTag,
        String tagColor) {

    /** @return {@code true} when this member holds authority in the channel (owner or moderator). */
    public boolean isAuthority() {
        return role == ChannelMemberRole.OWNER || role == ChannelMemberRole.CHANNEL_MODERATOR;
    }

    /** @return {@code true} when a distinct secondary tag (e.g. {@code STAFF}) should render. */
    public boolean hasSecondaryTag() {
        return secondaryTag != null && !secondaryTag.isBlank();
    }
}
