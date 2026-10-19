package org.hyzionstudios.mysticessentials.modules.lootbox.api.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** A physical key serial was revoked and can no longer open anything. */
public record KeyRevokedEvent(String serial, String keyId, String actor) implements MysticEvent {
}
