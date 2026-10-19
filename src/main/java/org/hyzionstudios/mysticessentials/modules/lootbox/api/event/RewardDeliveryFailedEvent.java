package org.hyzionstudios.mysticessentials.modules.lootbox.api.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** A delivery action failed or became ambiguous and was moved to REVIEW for staff. */
public record RewardDeliveryFailedEvent(String openingId, String fulfillmentId, java.util.UUID player, String type, String reason) implements MysticEvent {
}
