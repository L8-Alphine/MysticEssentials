package org.hyzionstudios.mysticessentials.modules.lootbox.api.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** Keys were issued to a player. {@code virtual} keys are a balance; physical ones are items (or pending-inbox entries for offline players). */
public record KeyGrantedEvent(java.util.UUID player, String keyId, int count, boolean virtual, String source, String actor) implements MysticEvent {
}
