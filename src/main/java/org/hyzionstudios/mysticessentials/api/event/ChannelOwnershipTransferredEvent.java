package org.hyzionstudios.mysticessentials.api.event;

import java.util.UUID;

/**
 * Fired after ownership of a temporary channel changes hands — via an accepted
 * transfer request, a staff-forced transfer, or owner-disconnect succession
 * (design bible §20.3). Purely informational; the ownership change is already
 * committed by the time this fires.
 */
public record ChannelOwnershipTransferredEvent(
    String channelId,
    UUID previousOwner,
    UUID newOwner,
    String source
) implements MysticEvent {

    /** How the transfer happened: {@code REQUEST}, {@code FORCED}, or {@code SUCCESSION}. */
    public String source() {
        return source;
    }
}
