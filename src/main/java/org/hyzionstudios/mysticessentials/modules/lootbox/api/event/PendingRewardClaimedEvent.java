package org.hyzionstudios.mysticessentials.modules.lootbox.api.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** A player claimed rewards from their pending inbox. */
public record PendingRewardClaimedEvent(java.util.UUID player, int claimed, int remaining) implements MysticEvent {
}
