package org.hyzionstudios.mysticessentials.api.service;

import java.util.UUID;

/**
 * Completes a {@link MailService#send} from a player whose recipient ignores them
 * ({@code /ignore}). Nothing was delivered, and the sender, when online, was already
 * told in the neutral words a refused private message uses.
 */
public final class MailBlockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID recipient;

    public MailBlockedException(UUID recipient) {
        super("The recipient does not accept mail from this sender");
        this.recipient = recipient;
    }

    /** The player who refused the mail. */
    public UUID recipient() {
        return recipient;
    }
}
