package com.alphine.mysticessentials.commands.hologram;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.commands.SharedSuggest;
import com.alphine.mysticessentials.hologram.model.HologramDefinition;
import com.alphine.mysticessentials.hologram.store.HologramStore;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Locale;

public final class HologramCmd {
    private HologramCmd() {
    }

    private static double center(double v) {
        return Math.floor(v) + 0.5D;
    }

    private static ServerPlayer requirePlayer(CommandSourceStack src) throws CommandSyntaxException {
        return src.getPlayerOrException();
    }

    private static HologramDefinition requireHolo(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        if (common.holograms == null) {
            src.sendFailure(Component.literal("§cHologram store not initialized."));
            return null;
        }
        HologramDefinition def = common.holograms.get(name);
        if (def == null) src.sendFailure(Component.literal("§cNo hologram named §f" + name + "§c."));
        return def;
    }

    private static int save(MysticEssentialsCommon common, CommandSourceStack src, HologramDefinition def) {
        try {
            common.holograms.save(def.name);
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("§cFailed to save: " + e.getMessage()));
            return 0;
        }
    }

    private static int saveAndDirty(MysticEssentialsCommon common, CommandSourceStack src, HologramDefinition def) {
        int r = save(common, src, def);
        if (r > 0 && common.hologramManager != null) {
            // immediate: adopt + spawn + apply now
            common.hologramManager.refreshNow(src.getServer(), def.name);
        }
        return r;
    }

    private static void markAllDirty(MysticEssentialsCommon common) {
        if (common.hologramManager == null || common.holograms == null) return;
        for (HologramDefinition def : common.holograms.list()) {
            common.hologramManager.markDirty(def.name);
        }
    }

    public static void register(MysticEssentialsCommon common, CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("holo")
                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_USE, 2))

                // -------- core ----------
                .then(Commands.literal("list")
                        .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_LIST, 2))
                        .executes(ctx -> list(common, ctx.getSource()))
                )

                .then(Commands.literal("create")
                        .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_CREATE, 2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> create(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                )

                .then(Commands.literal("delete")
                        .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_DELETE, 2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                .executes(ctx -> delete(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                )

                .then(Commands.literal("movehere")
                        .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_MOVE, 2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                .executes(ctx -> moveHere(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                )

                .then(Commands.literal("center")
                        .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_MOVE, 2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                .executes(ctx -> centerHolo(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                )

                .then(Commands.literal("tp")
                        .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_TELEPORT, 2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                .executes(ctx -> tp(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                )

                .then(Commands.literal("reload")
                        .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_RELOAD, 2))
                        .executes(ctx -> reload(common, ctx.getSource()))
                )

                // -------- lines ----------
                .then(Commands.literal("line")
                        .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_EDIT, 2))

                        .then(Commands.literal("list")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .executes(ctx -> lineList(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                                )
                        )

                        .then(Commands.literal("add")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_LINE_ADD, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(ctx -> lineAdd(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "text")))
                                        )
                                )
                        )

                        .then(Commands.literal("insert")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_LINE_ADD, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                                        .executes(ctx -> lineInsert(common, ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                                StringArgumentType.getString(ctx, "text")))
                                                )
                                        )
                                )
                        )

                        .then(Commands.literal("set")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_LINE_EDIT, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                                        .executes(ctx -> lineSet(common, ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                                StringArgumentType.getString(ctx, "text")))
                                                )
                                        )
                                )
                        )

                        .then(Commands.literal("del")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_LINE_DELETE, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                .executes(ctx -> lineDel(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        IntegerArgumentType.getInteger(ctx, "index")))
                                        )
                                )
                        )

                        .then(Commands.literal("clear")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_LINE_DELETE, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .executes(ctx -> lineClear(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                                )
                        )
                )

                // -------- settings ----------
                .then(Commands.literal("set")
                        .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_SETTING, 2))

                        .then(Commands.literal("seethrough")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_SETTING, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(ctx -> {
                                                    var def = requireHolo(common, ctx.getSource(), StringArgumentType.getString(ctx, "name"));
                                                    if (def == null) return 0;

                                                    boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                                                    def.visibility.seeThroughBlocks = enabled;

                                                    ctx.getSource().sendSystemMessage(Component.literal(
                                                            "§aSet seeThroughBlocks for §f" + def.name + " §ato §f" + enabled + "§a."
                                                    ));
                                                    return saveAndDirty(common, ctx.getSource(), def); // refreshNow() happens here
                                                })
                                        )
                                )
                        )

                        .then(Commands.literal("scale")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_SETTING_SCALE, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("value", FloatArgumentType.floatArg(0.01F, 8.0F))
                                                .executes(ctx -> setScale(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        FloatArgumentType.getFloat(ctx, "value")))
                                        )
                                )
                        )

                        .then(Commands.literal("rotation")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_SETTING_ROTATION, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("yaw", FloatArgumentType.floatArg(-180F, 180F))
                                                .executes(ctx -> setRotation(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        FloatArgumentType.getFloat(ctx, "yaw"), 0F))
                                                .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90F, 90F))
                                                        .executes(ctx -> setRotation(common, ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                FloatArgumentType.getFloat(ctx, "yaw"),
                                                                FloatArgumentType.getFloat(ctx, "pitch")))
                                                )
                                        )
                                )
                        )

                        .then(Commands.literal("billboard")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_SETTING_BILLBOARD, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggest.billboardModes(b))
                                                .executes(ctx -> setBillboard(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "mode")))
                                        )
                                )
                        )

                        .then(Commands.literal("viewdistance")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_SETTING_VIEWDISTANCE, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("blocks", IntegerArgumentType.integer(2, 256))
                                                .executes(ctx -> setViewDistance(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        IntegerArgumentType.getInteger(ctx, "blocks")))
                                        )
                                )
                        )

                        .then(Commands.literal("updateInterval")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_SETTING, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 20 * 60))
                                                .executes(ctx -> {
                                                    var def = requireHolo(common, ctx.getSource(), StringArgumentType.getString(ctx, "name"));
                                                    if (def == null) return 0;
                                                    def.updates.textUpdateIntervalTicks = IntegerArgumentType.getInteger(ctx, "ticks");
                                                    ctx.getSource().sendSystemMessage(Component.literal("§aUpdated text update interval."));
                                                    return saveAndDirty(common, ctx.getSource(), def);
                                                })
                                        )
                                )
                        )

                        .then(Commands.literal("placeholders")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_SETTING, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(ctx -> {
                                                    var def = requireHolo(common, ctx.getSource(), StringArgumentType.getString(ctx, "name"));
                                                    if (def == null) return 0;
                                                    def.updates.placeholdersEnabled = BoolArgumentType.getBool(ctx, "enabled");
                                                    ctx.getSource().sendSystemMessage(Component.literal("§aUpdated placeholdersEnabled."));
                                                    return saveAndDirty(common, ctx.getSource(), def);
                                                })
                                        )
                                )
                        )

                        .then(Commands.literal("percentPlaceholders")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_SETTING, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(ctx -> {
                                                    var def = requireHolo(common, ctx.getSource(), StringArgumentType.getString(ctx, "name"));
                                                    if (def == null) return 0;
                                                    def.updates.enablePercentPlaceholders = BoolArgumentType.getBool(ctx, "enabled");
                                                    ctx.getSource().sendSystemMessage(Component.literal("§aUpdated %% placeholders route."));
                                                    return saveAndDirty(common, ctx.getSource(), def);
                                                })
                                        )
                                )
                        )

                        .then(Commands.literal("bracePlaceholders")
                                .requires(src -> Perms.has(src, PermNodes.HOLOGRAM_SETTING, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggest.hologramNames(common, b))
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(ctx -> {
                                                    var def = requireHolo(common, ctx.getSource(), StringArgumentType.getString(ctx, "name"));
                                                    if (def == null) return 0;
                                                    def.updates.enableBracePlaceholders = BoolArgumentType.getBool(ctx, "enabled");
                                                    ctx.getSource().sendSystemMessage(Component.literal("§aUpdated {} placeholders route."));
                                                    return saveAndDirty(common, ctx.getSource(), def);
                                                })
                                        )
                                )
                        )
                )
        );
    }

    // ---------------- core impl ----------------

    private static int list(MysticEssentialsCommon common, CommandSourceStack src) {
        if (common.holograms == null) {
            src.sendFailure(Component.literal("§cHologram store not initialized."));
            return 0;
        }
        var all = common.holograms.list();
        src.sendSystemMessage(Component.literal("§eHolograms §7(" + all.size() + "):"));
        for (HologramDefinition h : all) {
            String status = h.enabled ? "§aenabled" : "§cdisabled";
            src.sendSystemMessage(Component.literal(" §7- §f" + h.name + " §8(" + status + "§8)"));
        }
        return all.size();
    }

    private static int create(MysticEssentialsCommon common, CommandSourceStack src, String name) throws CommandSyntaxException {
        ServerPlayer p = requirePlayer(src);
        if (common.holograms == null || common.cfg == null || common.cfg.holograms == null) {
            src.sendFailure(Component.literal("§cHologram system not ready."));
            return 0;
        }
        if (common.cfg.features != null && (!common.cfg.features.enableHologramSystem || !common.cfg.holograms.enabled)) {
            src.sendFailure(Component.literal("§cHologram system is disabled in config."));
            return 0;
        }
        if (common.holograms.get(name) != null) {
            src.sendFailure(Component.literal("§cA hologram with that name already exists."));
            return 0;
        }

        HologramDefinition def = new HologramDefinition();
        def.name = norm(name);
        def.enabled = true;

        def.anchor.type = "WORLD";
        def.anchor.dimension = p.serverLevel().dimension().location().toString();
        def.anchor.x = p.getX();
        def.anchor.y = p.getY();
        def.anchor.z = p.getZ();

        // defaults
        def.visibility.viewDistanceBlocks = common.cfg.holograms.defaultViewDistanceBlocks;
        def.style.lineSpacing = common.cfg.holograms.defaultLineSpacing;
        def.transform.scale = common.cfg.holograms.defaultScale;
        def.visibility.seeThroughBlocks = common.cfg.holograms.defaultSeeThroughBlocks;

        def.updates.placeholdersEnabled = common.cfg.holograms.defaultPlaceholdersEnabled;
        def.updates.textUpdateIntervalTicks = common.cfg.holograms.defaultTextUpdateIntervalTicks;
        def.updates.enablePercentPlaceholders = common.cfg.holograms.defaultEnablePercentPlaceholders;
        def.updates.enableBracePlaceholders = common.cfg.holograms.defaultEnableBracePlaceholders;

        // NEW DEFAULT LAYOUT
        def.lines.clear();
        def.lines.add("&7Edit your Hologram with the command");
        def.lines.add("&a/holo line set " + name + " 1 <text>");

        common.holograms.put(def);

        int saved = saveAndDirty(common, src, def);
        if (saved > 0) {
            src.sendSystemMessage(Component.literal("§aCreated hologram §f" + def.name + "§a."));
            src.sendSystemMessage(Component.literal("§7Tip: §a/holo line set " + def.name + " 1 <text>"));
        }
        return saved;
    }

    private static int delete(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        // REMOVE ENTITIES FIRST while we still know the anchor location
        if (common.hologramManager != null) {
            common.hologramManager.deleteNow(src.getServer(), def.name);
        }

        // THEN delete from disk/store
        try {
            common.holograms.delete(def.name);
        } catch (IOException e) {
            src.sendFailure(Component.literal("§cFailed to delete: " + e.getMessage()));
            return 0;
        }

        src.sendSystemMessage(Component.literal("§aDeleted hologram §f" + def.name + "§a."));
        return 1;
    }

    private static int moveHere(MysticEssentialsCommon common, CommandSourceStack src, String name) throws CommandSyntaxException {
        ServerPlayer p = requirePlayer(src);
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        if ("BOUND".equalsIgnoreCase(def.anchor.type)) {
            src.sendFailure(Component.literal("§cThat hologram is bound (NPC nameplate). Move the NPC instead."));
            return 0;
        }

        def.anchor.dimension = p.serverLevel().dimension().location().toString();
        def.anchor.x = p.getX();
        def.anchor.y = p.getY();
        def.anchor.z = p.getZ();

        src.sendSystemMessage(Component.literal("§aMoved hologram §f" + def.name + "§a."));
        return saveAndDirty(common, src, def);
    }

    private static int centerHolo(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        if ("BOUND".equalsIgnoreCase(def.anchor.type)) {
            src.sendFailure(Component.literal("§cThat hologram is bound (NPC nameplate). Center the NPC instead."));
            return 0;
        }

        def.anchor.x = center(def.anchor.x);
        def.anchor.z = center(def.anchor.z);

        src.sendSystemMessage(Component.literal("§aCentered hologram §f" + def.name + "§a."));
        return saveAndDirty(common, src, def);
    }

    private static int tp(MysticEssentialsCommon common, CommandSourceStack src, String name) throws CommandSyntaxException {
        ServerPlayer p = requirePlayer(src);
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        if ("BOUND".equalsIgnoreCase(def.anchor.type)) {
            src.sendFailure(Component.literal("§cThat hologram is bound (NPC nameplate). TP to the NPC instead."));
            return 0;
        }

        String cmd = String.format(Locale.ROOT, "execute in %s run tp %s %.3f %.3f %.3f",
                def.anchor.dimension, p.getName().getString(), def.anchor.x, def.anchor.y, def.anchor.z);

        src.getServer().getCommands().performPrefixedCommand(src.getServer().createCommandSourceStack(), cmd);
        return 1;
    }

    private static int reload(MysticEssentialsCommon common, CommandSourceStack src) {
        if (common.holograms == null) {
            src.sendFailure(Component.literal("§cHologram store not initialized."));
            return 0;
        }

        try {
            // reload defs from disk
            common.holograms.reloadAll();

            // rebuild runtime cache + chunk index
            if (common.hologramManager != null) {
                common.hologramManager.reloadAll(src.getServer());

                // INSTANT: recover/spawn/apply for chunks already loaded
                common.hologramManager.scanLoadedChunksAndRecover(src.getServer());
            }

            // mark dirty anyway (covers anything that loads later)
            markAllDirty(common);

        } catch (IOException e) {
            src.sendFailure(Component.literal("§cFailed to reload: " + e.getMessage()));
            return 0;
        }

        src.sendSystemMessage(Component.literal("§aReloaded holograms."));
        return 1;
    }

    // ---------------- line impl ----------------

    private static int lineList(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        src.sendSystemMessage(Component.literal("§eLines for §f" + def.name + "§e (" + def.lines.size() + "):"));
        for (int i = 0; i < def.lines.size(); i++) {
            src.sendSystemMessage(Component.literal(" §7" + (i + 1) + ". §f" + def.lines.get(i)));
        }
        return def.lines.size();
    }

    private static int lineAdd(MysticEssentialsCommon common, CommandSourceStack src, String name, String text) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        def.lines.add(text);
        src.sendSystemMessage(Component.literal("§aAdded line §7#" + def.lines.size() + " §ato §f" + def.name + "§a."));
        return saveAndDirty(common, src, def);
    }

    private static int lineInsert(MysticEssentialsCommon common, CommandSourceStack src, String name, int index1, String text) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        int idx = index1 - 1;
        if (idx < 0 || idx > def.lines.size()) {
            src.sendFailure(Component.literal("§cIndex out of range. Valid: 1-" + (def.lines.size() + 1)));
            return 0;
        }

        def.lines.add(idx, text);
        src.sendSystemMessage(Component.literal("§aInserted line §7#" + index1 + " §ainto §f" + def.name + "§a."));
        return saveAndDirty(common, src, def);
    }

    private static int lineSet(MysticEssentialsCommon common, CommandSourceStack src, String name, int index1, String text) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        int idx = index1 - 1;
        if (idx < 0 || idx >= def.lines.size()) {
            src.sendFailure(Component.literal("§cIndex out of range. Valid: 1-" + def.lines.size()));
            return 0;
        }

        def.lines.set(idx, text);
        src.sendSystemMessage(Component.literal("§aSet line §7#" + index1 + " §aon §f" + def.name + "§a."));
        return saveAndDirty(common, src, def);
    }

    private static int lineDel(MysticEssentialsCommon common, CommandSourceStack src, String name, int index1) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        int idx = index1 - 1;
        if (idx < 0 || idx >= def.lines.size()) {
            src.sendFailure(Component.literal("§cIndex out of range. Valid: 1-" + def.lines.size()));
            return 0;
        }

        def.lines.remove(idx);
        src.sendSystemMessage(Component.literal("§aDeleted line §7#" + index1 + " §afrom §f" + def.name + "§a."));
        return saveAndDirty(common, src, def);
    }

    private static int lineClear(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        def.lines.clear();
        src.sendSystemMessage(Component.literal("§aCleared all lines for §f" + def.name + "§a."));
        return saveAndDirty(common, src, def);
    }

    // ---------------- settings impl ----------------

    private static int setScale(MysticEssentialsCommon common, CommandSourceStack src, String name, float scale) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        def.transform.scale = scale;
        src.sendSystemMessage(Component.literal("§aSet scale of §f" + def.name + " §ato §f" + scale + "§a."));
        return saveAndDirty(common, src, def);
    }

    private static int setRotation(MysticEssentialsCommon common, CommandSourceStack src, String name, float yaw, float pitch) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        def.transform.yaw = yaw;
        def.transform.pitch = pitch;
        src.sendSystemMessage(Component.literal("§aSet rotation of §f" + def.name + "§a."));
        return saveAndDirty(common, src, def);
    }

    private static int setBillboard(MysticEssentialsCommon common, CommandSourceStack src, String name, String mode) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        def.transform.billboard = mode.toUpperCase(Locale.ROOT);
        src.sendSystemMessage(Component.literal("§aSet billboard of §f" + def.name + " §ato §f" + def.transform.billboard + "§a."));
        return saveAndDirty(common, src, def);
    }

    private static int setViewDistance(MysticEssentialsCommon common, CommandSourceStack src, String name, int blocks) {
        HologramDefinition def = requireHolo(common, src, name);
        if (def == null) return 0;

        def.visibility.viewDistanceBlocks = blocks;
        src.sendSystemMessage(Component.literal("§aSet view distance of §f" + def.name + " §ato §f" + blocks + "§a blocks."));
        return saveAndDirty(common, src, def);
    }

    private static String norm(String name) {
        String k = HologramStore.normalizeKey(name);
        return k == null ? name : k;
    }
}
