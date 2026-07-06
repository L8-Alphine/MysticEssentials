package org.hyzionstudios.mysticessentials.core.placeholder;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import at.helpch.placeholderapi.expansion.PlaceholderExpansion;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Exposes Mystic Essentials' internal placeholders to other plugins through
 * PlaceholderAPI under the {@code %mystic_<name>%} identifier (e.g.
 * {@code %mystic_player_name%}, {@code %mystic_group%}). Each request is routed
 * back to the shared internal resolver registry, so anything a module registers
 * with the {@code PlaceholderService} is automatically available externally.
 *
 * <p>Only referenced when PlaceholderAPI is present (see
 * {@link PlaceholderServiceImpl#init}), so its PlaceholderAPI supertype is never
 * loaded on servers without the integration.</p>
 */
public final class MysticExpansion extends PlaceholderExpansion {

    private final MysticCore core;
    private final PlaceholderServiceImpl service;

    public MysticExpansion(MysticCore core, PlaceholderServiceImpl service) {
        this.core = core;
        this.service = service;
    }

    @Override
    public String getIdentifier() {
        return "mystic";
    }

    @Override
    public String getAuthor() {
        return "HyzionStudios";
    }

    @Override
    public String getVersion() {
        return core.getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(PlayerRef player, String params) {
        UUID uuid = player == null ? null : player.getUuid();
        return service.resolveMysticToken(uuid, params);
    }
}
