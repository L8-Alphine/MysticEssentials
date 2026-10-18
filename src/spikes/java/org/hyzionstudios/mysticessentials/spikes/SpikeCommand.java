package org.hyzionstudios.mysticessentials.spikes;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * {@code /lbspike} — the in-game driver for the spikes. Every subcommand that
 * touches the world or the player's UI hops to the player's world thread first.
 */
final class SpikeCommand extends AbstractCommand {

    static final String PERMISSION = "mysticlootboxspikes.use";

    private static final String USAGE = String.join("\n",
            "/lbspike look                       S6: raycast the block you look at",
            "/lbspike select                     S6: next hit/break within 60 s selects the block",
            "/lbspike probe on|off               S6: log every block event (default on)",
            "/lbspike s2 variant [persist|persistengine] [root=<RootInteractionId>]   S2: swap the looked-at block",
            "/lbspike s2 restore | verify | autotest | letrun on|off",
            "/lbspike ui <" + String.join("|", WidgetTestPage.DOCUMENTS.keySet()) + ">   S3",
            "/lbspike anim <hz> <seconds>        S7: reveal animation cadence",
            "/lbspike results                    where the results log is");

    private final LootBoxSpikesPlugin plugin;

    SpikeCommand(LootBoxSpikesPlugin plugin) {
        super("lbspike", "DEV ONLY: LootBox Phase 0 spike harness");
        this.plugin = plugin;
        requirePermission(PERMISSION);
        setAllowsExtraArguments(true);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        try {
            run(context, arguments(context));
        } catch (Throwable t) {
            plugin.log().line("CMD", "command failed: " + t);
            context.sendMessage(Message.raw("[LBSpike] Command failed: " + t));
        }
        return CompletableFuture.completedFuture(null);
    }

    private void run(CommandContext context, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "look" -> onWorldThread(context, (player, ref, store) -> plugin.variants().look(player, ref, store));
            case "select" -> withPlayer(context, player -> {
                plugin.probe().startSelecting(player);
                reply(context, "Selection mode for 60 s: hit or break a block (try bare hand, a tool, a weapon,"
                        + " creative mode, and a soft block such as a flower).");
            });
            case "probe" -> {
                boolean on = args.length < 2 || !"off".equalsIgnoreCase(args[1]);
                plugin.probe().setVerbose(on);
                reply(context, "Block event logging " + (on ? "on" : "off") + ".");
            }
            case "s2" -> s2(context, args);
            case "ui" -> {
                String widget = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
                if (!WidgetTestPage.DOCUMENTS.containsKey(widget)) {
                    reply(context, "Widgets: " + String.join(", ", WidgetTestPage.DOCUMENTS.keySet()));
                    return;
                }
                onWorldThread(context, (player, ref, store) -> open(ref, store,
                        new WidgetTestPage(player, widget, plugin.log(), InventoryComponent.getItemInHand(store, ref))));
            }
            case "anim" -> {
                int hz = args.length > 1 ? clamp(parse(args[1], 10), 1, 60) : 10;
                int seconds = args.length > 2 ? clamp(parse(args[2], 4), 1, 30) : 4;
                onWorldThread(context, (player, ref, store) -> open(ref, store,
                        new AnimationTestPage(player, plugin, hz, seconds)));
            }
            case "results" -> reply(context, "Results log: " + plugin.log().file().toAbsolutePath());
            default -> reply(context, USAGE);
        }
    }

    private void s2(CommandContext context, String[] args) {
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "variant" -> {
                S2VariantSpike.Persist persist = Arrays.stream(args).anyMatch("persist"::equalsIgnoreCase)
                        ? S2VariantSpike.Persist.RAW
                        : Arrays.stream(args).anyMatch("persistengine"::equalsIgnoreCase)
                                ? S2VariantSpike.Persist.ENGINE : S2VariantSpike.Persist.NONE;
                String root = Arrays.stream(args).filter(a -> a.toLowerCase(Locale.ROOT).startsWith("root="))
                        .map(a -> a.substring(5)).findFirst().orElse(null);
                onWorldThread(context, (player, ref, store) ->
                        plugin.variants().makeVariant(player, ref, store, persist, root));
            }
            case "restore" -> onWorldThread(context, (player, ref, store) ->
                    plugin.variants().restore(player, ref, store));
            case "verify" -> onWorldThread(context, (player, ref, store) ->
                    plugin.variants().verify(player, store.getExternalData().getWorld()));
            case "autotest" -> {
                plugin.variants().autotest();
                reply(context, "S2 autotest finished; see " + plugin.log().file().getFileName());
            }
            case "letrun" -> {
                boolean on = args.length > 2 && "on".equalsIgnoreCase(args[2]);
                plugin.probe().setLetVariantUseRun(on);
                reply(context, on ? "Variant Use now runs (tests the root interaction)."
                        : "Variant Use is cancelled again (crate behaviour).");
            }
            default -> reply(context, USAGE);
        }
    }

    // ----- Plumbing -----------------------------------------------------------

    @FunctionalInterface
    private interface WorldAction {
        void run(PlayerRef player, Ref<EntityStore> ref, Store<EntityStore> store);
    }

    private void withPlayer(CommandContext context, Consumer<PlayerRef> action) {
        PlayerRef player = context.isPlayer() ? context.senderAs(PlayerRef.class) : null;
        if (player == null) {
            reply(context, "Run this in-game.");
            return;
        }
        action.accept(player);
    }

    private void onWorldThread(CommandContext context, WorldAction action) {
        withPlayer(context, player -> {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid()) {
                reply(context, "You are not in a world.");
                return;
            }
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            world.execute(() -> {
                if (!ref.isValid()) {
                    return;
                }
                try {
                    action.run(player, ref, store);
                } catch (Throwable t) {
                    plugin.log().line("CMD", "world-thread action failed: " + t);
                    player.sendMessage(Message.raw("[LBSpike] Failed: " + t));
                }
            });
        });
    }

    private static void open(Ref<EntityStore> ref, Store<EntityStore> store, CustomUIPage page) {
        Player entity = store.getComponent(ref, Player.getComponentType());
        if (entity != null) {
            entity.getPageManager().openCustomPage(ref, store, page);
        }
    }

    private static String[] arguments(CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) {
            return new String[0];
        }
        String[] tokens = input.trim().split("\\s+");
        int skip = tokens.length > 0 && tokens[0].replace("/", "").equalsIgnoreCase("lbspike") ? 1 : 0;
        return Arrays.copyOfRange(tokens, skip, tokens.length);
    }

    private static void reply(CommandContext context, String text) {
        context.sendMessage(Message.raw("[LBSpike] " + text));
    }

    private static int parse(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
