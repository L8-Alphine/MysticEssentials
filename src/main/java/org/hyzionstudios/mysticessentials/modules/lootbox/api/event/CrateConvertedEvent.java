package org.hyzionstudios.mysticessentials.modules.lootbox.api.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** A block became a crate bound to a pool. */
public record CrateConvertedEvent(String crateId, String lootboxId, String worldId, int x, int y, int z, String blockKey, String actor) implements MysticEvent {
}
