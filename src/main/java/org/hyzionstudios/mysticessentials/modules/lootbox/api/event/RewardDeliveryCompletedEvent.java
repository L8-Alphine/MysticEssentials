package org.hyzionstudios.mysticessentials.modules.lootbox.api.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** One delivery action of an opening finished ({@code state} is DELIVERED or PENDING_INBOX). */
public record RewardDeliveryCompletedEvent(String openingId, String fulfillmentId, java.util.UUID player, String type, String state) implements MysticEvent {
}
