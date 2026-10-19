package org.hyzionstudios.mysticessentials.modules.lootbox.api.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** A crate was removed, or disabled because its block changed or was destroyed. {@code reason} says which. */
public record CrateRemovedEvent(String crateId, String lootboxId, String reason, String actor) implements MysticEvent {
}
