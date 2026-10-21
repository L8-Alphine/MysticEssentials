package org.hyzionstudios.mysticessentials.modules.lootbox.api.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** An opening committed: the key is spent and the outcome is fixed. Listeners are notified only and cannot change the result. */
public record OpeningCommittedEvent(String openingId, java.util.UUID player, String lootboxId, int revision, String rewardId, String probability, String oddsId) implements MysticEvent {
}
