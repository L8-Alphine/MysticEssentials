package org.hyzionstudios.mysticessentials.core.placeholder;

import java.util.List;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import at.helpch.placeholderapi.expansion.PlaceholderExpansion;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Exposes Mystic Essentials' internal placeholders to other mods through
 * PlaceholderAPI (e.g. {@code %mystic_playtime_total%},
 * {@code %mystic_player_name%}, {@code %mystic_group%}). Each request is routed
 * back to the shared internal resolver registry, so anything a module registers
 * with the {@code PlaceholderService} — including the playtime counters — is
 * automatically available externally, with no per-placeholder wiring.
 *
 * <p>One instance is registered per identifier ({@code mystic} and the longer
 * {@code mysticessentials} alias) so either spelling resolves.</p>
 *
 * <p>Only referenced when PlaceholderAPI is present (see
 * {@link PlaceholderServiceImpl#init}), so its PlaceholderAPI supertype is never
 * loaded on servers without the integration.</p>
 */
public final class MysticExpansion extends PlaceholderExpansion {

    private final MysticCore core;
    private final PlaceholderServiceImpl service;
    private final String identifier;

    public MysticExpansion(MysticCore core, PlaceholderServiceImpl service, String identifier) {
        this.core = core;
        this.service = service;
        this.identifier = identifier;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getName() {
        return "MysticEssentials";
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
    public String getDescription() {
        return "Player profile, rank, and playtime placeholders from Mystic Essentials.";
    }

    @Override
    public boolean persist() {
        return true;
    }

    /**
     * Advertises the live placeholder list so {@code /papi info} and expansion
     * listings document what is available (playtime included). Computed on each
     * call because modules register their own placeholders as they enable.
     */
    @Override
    public List<String> getPlaceholders() {
        return service.registeredNames().stream()
                .map(name -> "%" + identifier + "_" + name + "%")
                .toList();
    }

    @Override
    public String onPlaceholderRequest(PlayerRef player, String params) {
        UUID uuid = player == null ? null : player.getUuid();
        return service.resolveMysticToken(uuid, params);
    }
}
