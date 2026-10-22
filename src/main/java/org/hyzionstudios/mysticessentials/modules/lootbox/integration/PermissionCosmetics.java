package org.hyzionstudios.mysticessentials.modules.lootbox.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.api.service.PermissionService;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;

/**
 * The built-in COSMETIC provider {@code permission} (P3-WP9): a cosmetic that
 * another plugin unlocks through a permission node (a chat tag, a title, a
 * cape), granted permanently through LuckPerms.
 *
 * <pre>{"type": "COSMETIC", "provider": "permission", "duplicates": "EXCLUDE_OWNED",
 *  "params": {"node": "cosmetics.cape.royal", "name": "Royal Cape"}}</pre>
 *
 * <p>Idempotent: adding a node the player already has changes nothing. Owns
 * a cosmetic when the (online) player has the node, which is what duplicate
 * protection asks just before the roll. Registered only when LuckPerms is
 * there to grant nodes.</p>
 */
final class PermissionCosmetics implements RewardProvider {

    static final String ID = "permission";
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");

    private final PermissionService permissions;

    PermissionCosmetics(PermissionService permissions) {
        this.permissions = permissions;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String kind() {
        return "COSMETIC";
    }

    @Override
    public Guarantee guarantee() {
        return Guarantee.IDEMPOTENT;
    }

    @Override
    public List<String> validate(Map<String, String> params) {
        List<String> problems = new ArrayList<>();
        String node = params.get("node");
        if (node == null || !NODE.matcher(node.trim()).matches()) {
            problems.add("node must be a permission node without wildcards, e.g. cosmetics.cape.royal");
        }
        String name = params.get("name");
        if (name != null && name.length() > 64) {
            problems.add("name must be at most 64 characters");
        }
        return problems;
    }

    @Override
    public String describe(Map<String, String> params) {
        String name = params.get("name");
        return "Cosmetic: " + (name == null || name.isBlank() ? params.getOrDefault("node", "?").trim() : name.trim());
    }

    @Override
    public CompletableFuture<Result> grant(String fulfillmentId, UUID player, Map<String, String> params) {
        String node = params.getOrDefault("node", "").trim();
        if (!NODE.matcher(node).matches()) {
            return CompletableFuture.completedFuture(Result.failed("bad node " + node));
        }
        if (!permissions.canGrant()) {
            return CompletableFuture.completedFuture(Result.failed("LuckPerms is not available"));
        }
        return permissions.grant(player, node, null).handle((ok, error) -> {
            if (error != null) {
                return Result.unknown("LuckPerms threw " + error);
            }
            return Boolean.TRUE.equals(ok) ? Result.delivered("node " + node) : Result.failed("LuckPerms refused " + node);
        });
    }

    @Override
    public boolean supportsOwnership() {
        return true;
    }

    @Override
    public boolean owns(UUID player, Map<String, String> params) {
        String node = params.getOrDefault("node", "").trim();
        return NODE.matcher(node).matches() && permissions.has(player, node);
    }
}
