package org.hyzionstudios.mysticessentials.modules.craftblock;

import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTranslationProperties;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;

/**
 * Craft blocking: stops configured item ids from ever being produced by a
 * crafting recipe, on every bench and in the hand-crafting menu alike.
 *
 * <p>Enforcement is a single ECS listener on {@code CraftRecipeEvent.Pre}
 * ({@link CraftRecipeBlockSystem}) — the engine's own veto point, fired before
 * ingredients are consumed by both the instant and the timed crafting paths.
 * A recipe is denied when its recipe id or <b>any</b> of its output item ids
 * matches {@code modules/craftblock/config.json}, so one item id covers every
 * recipe that yields it.</p>
 *
 * <p>Blocked recipes still appear in the client's bench UI — the client owns
 * that list and 0.6.2 still exposes no per-player recipe filtering — so a denied
 * craft sends the player a Danger toast explaining why instead of failing
 * silently. Staff with {@link Permissions#CRAFTBLOCK_BYPASS} (or the per-item
 * node) craft normally.</p>
 *
 * <p><b>Not covered:</b> processing benches (smelters and the like) run their
 * recipes through {@code ProcessingBenchBlock}, which never fires
 * {@code CraftRecipeEvent}. Nothing in 0.6.2 can veto those either — Update 6
 * left {@code ProcessingBenchBlock} without a veto point.</p>
 */
public final class CraftBlockModule extends AbstractMysticModule {

    /** Cooldown between denial notices for one player, so held-down crafting cannot spam. */
    private static final long NOTIFY_COOLDOWN_SECONDS = 3;
    private static final String NOTIFY_COOLDOWN_KEY = "craftblock-notify";

    private volatile CraftBlockConfig config = new CraftBlockConfig();
    private volatile CraftBlockRules rules = CraftBlockRules.empty();

    /**
     * The crafting veto stays registered for the plugin's lifetime: the entity
     * store registry rejects a second registration of the same system class, so
     * hot-disabling this module flips {@link #active} instead of unregistering.
     */
    private CraftRecipeBlockSystem system;
    private volatile boolean active;

    public CraftBlockModule() {
        super("craftblock", "Craft Blocking", "1.0.0");
    }

    @Override
    public void onEnable() {
        applyConfig();
        registerCommand(new CraftBlockCommand());
        if (system == null) {
            CraftRecipeBlockSystem candidate = new CraftRecipeBlockSystem(this);
            if (core.platform().registerEntitySystem(candidate)) {
                system = candidate;
            } else {
                log("Could not install the crafting listener — nothing will be blocked.");
                return;
            }
        }
        active = true;
    }

    @Override
    public void onReload() {
        applyConfig();
    }

    @Override
    public void onDisable() {
        active = false;
    }

    private void applyConfig() {
        config = core.configManager().loadModuleConfig(id(), CraftBlockConfig.class, new CraftBlockConfig());
        rules = CraftBlockRules.compile(config.blockedItems);
    }

    // ----- Enforcement -----------------------------------------------------------

    /**
     * Decides whether a craft must be denied. Called on the crafting player's
     * world thread from {@link CraftRecipeBlockSystem}.
     *
     * @return the blocked id that matched (for the denial message), or
     *         {@code null} when the craft may proceed
     */
    String blockedIdFor(PlayerRef player, CraftingRecipe recipe) {
        if (!active || recipe == null) {
            return null;
        }
        CraftBlockRules current = rules;
        if (current.isEmpty()) {
            return null;
        }
        String matched = matchedId(current, recipe);
        if (matched == null || isBypassed(player, matched)) {
            return null;
        }
        return matched;
    }

    /** Matches the recipe's outputs first (what the config is written in terms of), then its id. */
    private static String matchedId(CraftBlockRules current, CraftingRecipe recipe) {
        String primary = itemIdOf(recipe.getPrimaryOutput());
        if (primary != null && current.matches(primary)) {
            return primary;
        }
        MaterialQuantity[] outputs = recipe.getOutputs();
        if (outputs != null) {
            for (MaterialQuantity output : outputs) {
                String itemId = itemIdOf(output);
                if (itemId != null && current.matches(itemId)) {
                    return itemId;
                }
            }
        }
        String recipeId = recipe.getId();
        return recipeId != null && current.matches(recipeId) ? recipeId : null;
    }

    private static String itemIdOf(MaterialQuantity material) {
        return material == null ? null : material.getItemId();
    }

    /** Staff bypass: the blanket node, or the per-item {@code ...bypass.<item id>} node. */
    private boolean isBypassed(PlayerRef player, String itemId) {
        if (player == null) {
            return false;
        }
        try {
            return player.hasPermission(Permissions.CRAFTBLOCK_BYPASS)
                    || player.hasPermission(Permissions.craftBlockBypass(itemId));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Tells the player why the craft did nothing. The bench window is open and
     * covering chat, so the default surface is the client toast — the same one
     * the server uses for "missing ingredient".
     */
    void onCraftDenied(PlayerRef player, String itemId) {
        if (config.logAttempts) {
            core.log(Level.INFO, "[craftblock] " + (player == null ? "?" : player.getUsername())
                    + " tried to craft blocked item '" + itemId + "'.");
        }
        if (player == null) {
            return;
        }
        // One notice per few seconds: a click-and-hold craft fires Pre per attempt.
        if (core.cooldowns().isActive(player.getUuid(), NOTIFY_COOLDOWN_KEY)) {
            return;
        }
        core.cooldowns().set(player.getUuid(), NOTIFY_COOLDOWN_KEY, NOTIFY_COOLDOWN_SECONDS);
        if (config.notifyPlayer) {
            try {
                NotificationUtil.sendNotification(player.getPacketHandler(),
                        core.getMessageService().format("&cThis item cannot be crafted."),
                        displayName(itemId), NotificationStyle.Danger);
            } catch (Throwable t) {
                core.log(Level.FINE, "[craftblock] Toast failed for " + player.getUsername() + ": " + t);
            }
        }
        if (config.messageInChat) {
            core.getMessageService().sendKey(player, "craftblock-denied", Map.of("item", itemId));
        }
    }

    /**
     * The item's translated client name when the id resolves to an item asset,
     * else the raw id. Name arguments are re-applied — a templated name such as
     * {@code "{material} Longsword"} renders the placeholder literally without
     * them.
     */
    private static Message displayName(String itemId) {
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            String key = item == null ? null : item.getTranslationKey();
            if (key == null) {
                return Message.raw(itemId);
            }
            Message name = Message.translation(key);
            ItemTranslationProperties properties = item.getTranslationProperties();
            Map<String, Message> arguments = properties == null ? null : properties.getNameArguments();
            if (arguments != null) {
                for (Map.Entry<String, Message> argument : arguments.entrySet()) {
                    name = name.param(argument.getKey(), argument.getValue());
                }
            }
            return name;
        } catch (Throwable ignored) {
            // Unknown/foreign id — fall back to the raw id.
            return Message.raw(itemId);
        }
    }

    // ----- Commands ----------------------------------------------------------------

    /**
     * {@code /craftblock} lists the active block list; {@code /craftblock check}
     * prints the id of the held item, which is how an admin finds the id to add.
     * The list itself is config-owned — edit
     * {@code modules/craftblock/config.json} and run {@code /mystic reload}.
     */
    private final class CraftBlockCommand extends MysticCommand {

        CraftBlockCommand() {
            super(CraftBlockModule.this.core, "craftblock", "Show blocked crafting items.");
            requirePermission(Permissions.CRAFTBLOCK_ADMIN);
            allowExtraArguments();
        }

        @Override
        protected void run(MysticCommandSender sender) {
            String sub = sender.arg(0).orElse("").toLowerCase(Locale.ROOT);
            if (sub.equals("check")) {
                checkHeld(sender);
                return;
            }
            CraftBlockRules current = rules;
            if (!active || current.isEmpty()) {
                sender.replyKey("craftblock-none");
                return;
            }
            sender.replyKey("craftblock-list-header",
                    Map.of("count", String.valueOf(current.entries().size())));
            for (String entry : current.entries()) {
                sender.replyKey("craftblock-list-entry", Map.of("item", entry));
            }
        }

        // InventoryComponent.getItemInHand is the held-item accessor, as elsewhere in
        // this mod: the active tool item while using tools, else the hotbar item.
        private void checkHeld(MysticCommandSender sender) {
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            boolean dispatched = core.platform().runOnEntityThread(player, (store, ref, world) -> {
                ItemStack held = InventoryComponent.getItemInHand(store, ref);
                if (held == null || held.isEmpty()) {
                    core.getMessageService().sendKey(player, "craftblock-check-empty");
                    return;
                }
                String itemId = held.getItemId();
                core.getMessageService().sendKey(player,
                        rules.matches(itemId) ? "craftblock-check-blocked" : "craftblock-check-allowed",
                        Map.of("item", String.valueOf(itemId)));
            });
            if (!dispatched) {
                sender.replyKey("craftblock-check-empty");
            }
        }
    }
}
