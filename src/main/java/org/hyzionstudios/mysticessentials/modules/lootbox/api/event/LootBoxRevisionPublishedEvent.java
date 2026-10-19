package org.hyzionstudios.mysticessentials.modules.lootbox.api.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** A pool's draft (or an older revision, on rollback) became its active revision. {@code rolledBackFrom} is null for a normal publish. */
public record LootBoxRevisionPublishedEvent(String lootboxId, int revision, String contentHash, String actor, Integer rolledBackFrom) implements MysticEvent {
}
