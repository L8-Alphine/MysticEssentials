package org.hyzionstudios.mysticessentials.modules.lootbox.api.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** Something suspicious happened around an opening: a forged or replayed key, a crate whose block changed. For moderation hooks. */
public record OpeningFlaggedEvent(java.util.UUID player, String lootboxId, String reason, String detail) implements MysticEvent {
}
