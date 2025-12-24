package com.alphine.mysticessentials.commands.npc;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.commands.SharedSuggest;
import com.alphine.mysticessentials.npc.model.NpcDefinition;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Supplier;

public final class NpcCmd {
    private NpcCmd() {}

    private static double center(double v) { return Math.floor(v) + 0.5D; }

    private static ServerPlayer requirePlayer(CommandSourceStack src) throws CommandSyntaxException {
        return src.getPlayerOrException();
    }

    private static NpcDefinition requireNpc(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        if (common.npcs == null) {
            src.sendFailure(Component.literal("§cNPC store not initialized."));
            return null;
        }
        NpcDefinition def = common.npcs.get(name);
        if (def == null) src.sendFailure(Component.literal("§cNo NPC named §f" + name + "§c."));
        return def;
    }

    private static int save(MysticEssentialsCommon common, CommandSourceStack src, NpcDefinition def) {
        try {
            common.npcs.save(def.name);
            System.out.println("Saved NPC " + def.name);
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("§cFailed to save: " + e.getMessage()));
            return 0;
        }
    }

    private static ArrayList<String> actionList(NpcDefinition def, String click) {
        // assumes: def.interactions is Map<String, List<String>>
        def.interactions.putIfAbsent(click, new ArrayList<>());
        var list = def.interactions.get(click);
        if (list instanceof ArrayList<String> al) return al;
        return new ArrayList<>(list);
    }

    public static void register(MysticEssentialsCommon common, CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("npc")
                .requires(src -> Perms.has(src, PermNodes.NPC_USE, 2))

                // core
                .then(Commands.literal("list")
                        .requires(src -> Perms.has(src, PermNodes.NPC_LIST, 2))
                        .executes(ctx -> list(common, ctx.getSource()))
                )

                .then(Commands.literal("create")
                        .requires(src -> Perms.has(src, PermNodes.NPC_CREATE, 2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> createDefault(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                                .then(Commands.literal("entity")
                                        .then(Commands.argument("entityType", ResourceLocationArgument.id())
                                                .suggests((c, b) -> SharedSuggest.entityTypes(b))
                                                .executes(ctx -> {
                                                    var src = ctx.getSource();
                                                    String name = StringArgumentType.getString(ctx, "name");
                                                    ResourceLocation id = ResourceLocationArgument.getId(ctx, "entityType");

                                                    // validation
                                                    if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                                                        src.sendFailure(Component.literal("§cUnknown entity type §f" + id + "§c."));
                                                        return 0;
                                                    }

                                                    return createEntity(common, src, name, id.toString());
                                                })
                                        )
                                )
                                .then(Commands.literal("player")
                                        .then(Commands.argument("profileName", StringArgumentType.word())
                                                .executes(ctx -> createPlayer(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "profileName")))
                                        )
                                )
                        )
                )

                .then(Commands.literal("delete")
                        .requires(src -> Perms.has(src, PermNodes.NPC_DELETE, 2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                .executes(ctx -> delete(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                )

                .then(Commands.literal("movehere")
                        .requires(src -> Perms.has(src, PermNodes.NPC_EDIT, 2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                .executes(ctx -> moveHere(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                )

                .then(Commands.literal("center")
                        .requires(src -> Perms.has(src, PermNodes.NPC_EDIT, 2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                .executes(ctx -> centerNpc(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                )

                .then(Commands.literal("tp")
                        .requires(src -> Perms.has(src, PermNodes.NPC_TELEPORT, 2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                .executes(ctx -> tp(common, ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                )

                .then(Commands.literal("reload")
                        .requires(src -> Perms.has(src, PermNodes.NPC_RELOAD, 2))
                        .executes(ctx -> reload(common, ctx.getSource()))
                )

                // actions (command list modifications)
                .then(Commands.literal("action")
                        .requires(src -> Perms.has(src, PermNodes.NPC_SETTING_INTERACTION, 2))

                        .then(Commands.literal("list")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("click", StringArgumentType.word())
                                                .suggests((c,b) -> SharedSuggest.clickTypes(b))
                                                .executes(ctx -> actionList(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "click")))
                                        )
                                )
                        )

                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("click", StringArgumentType.word())
                                                .suggests((c,b) -> SharedSuggest.clickTypes(b))
                                                .then(Commands.argument("cmd", StringArgumentType.greedyString())
                                                        .executes(ctx -> actionAdd(common, ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "click"),
                                                                StringArgumentType.getString(ctx, "cmd")))
                                                ))
                                )
                        )

                        .then(Commands.literal("insert")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("click", StringArgumentType.word())
                                                .suggests((c,b) -> SharedSuggest.clickTypes(b))
                                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                        .then(Commands.argument("cmd", StringArgumentType.greedyString())
                                                                .executes(ctx -> actionInsert(common, ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "name"),
                                                                        StringArgumentType.getString(ctx, "click"),
                                                                        IntegerArgumentType.getInteger(ctx, "index"),
                                                                        StringArgumentType.getString(ctx, "cmd")))
                                                        )))
                                )
                        )

                        .then(Commands.literal("set")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("click", StringArgumentType.word())
                                                .suggests((c,b) -> SharedSuggest.clickTypes(b))
                                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                        .then(Commands.argument("cmd", StringArgumentType.greedyString())
                                                                .executes(ctx -> actionSet(common, ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "name"),
                                                                        StringArgumentType.getString(ctx, "click"),
                                                                        IntegerArgumentType.getInteger(ctx, "index"),
                                                                        StringArgumentType.getString(ctx, "cmd")))
                                                        )))
                                )
                        )

                        .then(Commands.literal("del")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("click", StringArgumentType.word())
                                                .suggests((c,b) -> SharedSuggest.clickTypes(b))
                                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> actionDel(common, ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "click"),
                                                                IntegerArgumentType.getInteger(ctx, "index")))
                                                ))
                                )
                        )

                        .then(Commands.literal("clear")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("click", StringArgumentType.word())
                                                .suggests((c,b) -> SharedSuggest.clickTypes(b))
                                                .executes(ctx -> actionClear(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "click")))
                                        )
                                )
                        )
                )

                // settings
                .then(Commands.literal("set")
                        .requires(src -> Perms.has(src, PermNodes.NPC_SETTING, 2))

                        .then(Commands.literal("scale")
                                .requires(src -> Perms.has(src, PermNodes.NPC_SETTING_SCALE, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("value", FloatArgumentType.floatArg(0.01F, 8.0F))
                                                .executes(ctx -> setScale(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        FloatArgumentType.getFloat(ctx, "value")))
                                        )
                                )
                        )

                        .then(Commands.literal("glow")
                                .requires(src -> Perms.has(src, PermNodes.NPC_SETTING_VISIBILITY, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(ctx -> setGlow(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        BoolArgumentType.getBool(ctx, "enabled")))
                                        )
                                )
                        )

                        .then(Commands.literal("glowColor")
                                .requires(src -> Perms.has(src, PermNodes.NPC_SETTING_VISIBILITY, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("color", StringArgumentType.word())
                                                .executes(ctx -> setGlowColor(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "color")))
                                        )
                                )
                        )

                        .then(Commands.literal("look")
                                .requires(src -> Perms.has(src, PermNodes.NPC_SETTING, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(ctx -> setLook(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        BoolArgumentType.getBool(ctx, "enabled")))
                                        )
                                )
                        )

                        .then(Commands.literal("lookRange")
                                .requires(src -> Perms.has(src, PermNodes.NPC_SETTING, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 128))
                                                .executes(ctx -> setLookRange(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        IntegerArgumentType.getInteger(ctx, "blocks")))
                                        )
                                )
                        )

                        .then(Commands.literal("hideName")
                                .then(Commands.argument("npc", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(ctx -> {
                                                    var npc = requireNpc(common, ctx.getSource(), StringArgumentType.getString(ctx, "npc"));
                                                    if (npc == null) return 0;
                                                    npc.nameplate.hideEntityName = BoolArgumentType.getBool(ctx, "enabled");
                                                    ctx.getSource().sendSystemMessage(Component.literal("§aUpdated hideEntityName."));
                                                    return save(common, ctx.getSource(), npc);
                                                })
                                        )
                                )
                        )

                        .then(Commands.literal("equip")
                                .requires(src -> Perms.has(src, PermNodes.NPC_SETTING_EQUIP, 2))

                                .then(Commands.literal("visible")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                                .then(Commands.argument("slot", StringArgumentType.word())
                                                        .suggests(SharedSuggest.equipSlots())
                                                        .then(Commands.argument("visible", BoolArgumentType.bool())
                                                                .executes(ctx -> equipSetVisible(common, ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "name"),
                                                                        StringArgumentType.getString(ctx, "slot"),
                                                                        BoolArgumentType.getBool(ctx, "visible")))
                                                        )
                                                )
                                        )
                                )

                                .then(Commands.literal("set")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                                .then(Commands.argument("slot", StringArgumentType.word())
                                                        .suggests((c,b) -> SharedSuggest.equipSlots().getSuggestions(c,b))
                                                        .executes(ctx -> equipSetFromHand(common, ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "slot"),
                                                                false
                                                        ))
                                                        .then(Commands.literal("offhand")
                                                                .executes(ctx -> equipSetFromHand(common, ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "name"),
                                                                        StringArgumentType.getString(ctx, "slot"),
                                                                        true
                                                                ))
                                                        )
                                                )
                                        )
                                )

                                .then(Commands.literal("clear")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                                .then(Commands.argument("slot", StringArgumentType.word())
                                                        .suggests((c,b) -> SharedSuggest.equipSlots().getSuggestions(c,b))
                                                        .executes(ctx -> equipClear(common, ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "slot")
                                                        ))
                                                )
                                        )
                                )

                                .then(Commands.literal("list")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                                .executes(ctx -> equipList(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name")
                                                ))
                                        )
                                )
                        )
                )

                // dialogue
                .then(Commands.literal("dialogue")
                        .requires(src -> Perms.has(src, PermNodes.NPC_DIALOGUE_ADD, 2)
                                || Perms.has(src, PermNodes.NPC_DIALOGUE_EDIT, 2)
                                || Perms.has(src, PermNodes.NPC_DIALOGUE_DELETE, 2))

                        .then(Commands.literal("list")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .executes(ctx -> dialogueList(common, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))
                                )
                        )

                        .then(Commands.literal("add")
                                .requires(src -> Perms.has(src, PermNodes.NPC_DIALOGUE_ADD, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("line", StringArgumentType.greedyString())
                                                .executes(ctx -> dialogueAdd(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "line")))
                                        )
                                )
                        )

                        .then(Commands.literal("set")
                                .requires(src -> Perms.has(src, PermNodes.NPC_DIALOGUE_EDIT, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("line", StringArgumentType.greedyString())
                                                        .executes(ctx -> dialogueSet(common, ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                                StringArgumentType.getString(ctx, "line")))
                                                ))
                                )
                        )

                        .then(Commands.literal("del")
                                .requires(src -> Perms.has(src, PermNodes.NPC_DIALOGUE_DELETE, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                .executes(ctx -> dialogueDel(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        IntegerArgumentType.getInteger(ctx, "index")))
                                        )
                                )
                        )

                        .then(Commands.literal("clear")
                                .requires(src -> Perms.has(src, PermNodes.NPC_DIALOGUE_DELETE, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .executes(ctx -> dialogueClear(common, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))
                                )
                        )

                        .then(Commands.literal("enabled")
                                .requires(src -> Perms.has(src, PermNodes.NPC_DIALOGUE_EDIT, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(ctx -> dialogueEnabled(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        BoolArgumentType.getBool(ctx, "enabled")))
                                        )
                                )
                        )

                        .then(Commands.literal("mode")
                                .requires(src -> Perms.has(src, PermNodes.NPC_DIALOGUE_EDIT, 2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .executes(ctx -> dialogueMode(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "mode")))
                                        )
                                )
                        )
                )

                // pathing
                .then(Commands.literal("path")
                        .requires(src -> Perms.has(src, PermNodes.NPC_EDIT, 2))

                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .executes(ctx -> pathAdd(common, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))
                                )
                        )

                        .then(Commands.literal("clear")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .executes(ctx -> pathClear(common, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))
                                )
                        )

                        .then(Commands.literal("enabled")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(ctx -> pathEnabled(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        BoolArgumentType.getBool(ctx, "enabled")))
                                        )
                                )
                        )

                        .then(Commands.literal("speed")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("bps", FloatArgumentType.floatArg(0.0F, 5.0F))
                                                .executes(ctx -> pathSpeed(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        FloatArgumentType.getFloat(ctx, "bps")))
                                        )
                                )
                        )

                        .then(Commands.literal("list")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .executes(ctx -> pathList(common, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))
                                )
                        )
                )

                .then(Commands.literal("skin")
                        .requires(src -> Perms.has(src, PermNodes.NPC_SKIN_CHANGE, 2))

                        .then(Commands.literal("set")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("source", StringArgumentType.word())
                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                        .executes(ctx -> skinSet(common, ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "source"),
                                                                StringArgumentType.getString(ctx, "value")))
                                                ))
                                )
                        )

                        .then(Commands.literal("player")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .then(Commands.argument("mcName", StringArgumentType.word())
                                                .executes(ctx -> skinSet(common, ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        "MOJANG_USER",
                                                        StringArgumentType.getString(ctx, "mcName")))
                                        )
                                )
                        )

                        .then(Commands.literal("mirrorViewer")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .executes(ctx -> skinSet(common, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                "MIRROR_VIEWER",
                                                ""))
                                )
                        )

                        .then(Commands.literal("random")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c,b) -> SharedSuggest.npcNames(common, b))
                                        .executes(ctx -> skinSet(common, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                "DEFAULT_RANDOM",
                                                ""))
                                )
                        )
                )
        );
    }

    // ---------- core impl ----------
    private static int list(MysticEssentialsCommon common, CommandSourceStack src) {
        if (common.npcs == null) { src.sendFailure(Component.literal("§cNPC store not initialized.")); return 0; }
        var all = common.npcs.list();
        src.sendSystemMessage(Component.literal("§eNPCs §7(" + all.size() + "):"));
        for (NpcDefinition n : all) {
            String status = n.enabled ? "§aenabled" : "§cdisabled";
            String kind = (n.type != null ? n.type.kind : "UNKNOWN");
            src.sendSystemMessage(Component.literal(" §7- §f" + n.name + " §8(" + kind + ", " + status + "§8)"));
        }
        return all.size();
    }

    private static void setAnchorInFrontOfPlayer(NpcDefinition def, ServerPlayer p, double distance) {
        def.anchor.dimension = p.serverLevel().dimension().location().toString();

        float yaw = p.getYRot(); // degrees
        double rad = Math.toRadians(yaw);

        double dx = -Math.sin(rad);
        double dz =  Math.cos(rad);

        def.anchor.x = p.getX() + dx * distance;
        def.anchor.y = p.getY();
        def.anchor.z = p.getZ() + dz * distance;

        def.anchor.yaw = yaw;
        def.anchor.pitch = p.getXRot();
    }

    private static int createDefault(MysticEssentialsCommon common, CommandSourceStack src, String name) throws CommandSyntaxException {
        return createEntity(common, src, name, "minecraft:villager");
    }

    private static int createEntity(MysticEssentialsCommon common, CommandSourceStack src, String name, String entityType) throws CommandSyntaxException {
        ServerPlayer p = requirePlayer(src);
        if (common.npcs == null || common.cfg == null || common.cfg.npcs == null) {
            src.sendFailure(Component.literal("§cNPC system not ready."));
            return 0;
        }
        if (common.cfg.features != null && (!common.cfg.features.enableNpcSystem || !common.cfg.npcs.enabled)) {
            src.sendFailure(Component.literal("§cNPC system is disabled in config."));
            return 0;
        }
        if (common.npcs.get(name) != null) {
            src.sendFailure(Component.literal("§cAn NPC with that name already exists."));
            return 0;
        }

        NpcDefinition def = new NpcDefinition();
        def.name = name;
        System.out.println("Creating NPC (Entity) with name " + name);
        def.enabled = true;

        setAnchorInFrontOfPlayer(def, p, 0.5D);
        System.out.println("Anchor set to dimension " + def.anchor.dimension + " at " + def.anchor.x + ", " + def.anchor.y + ", " + def.anchor.z);

        def.type.kind = "ENTITY";
        def.type.entityType = entityType;

        def.display.scale = common.cfg.npcs.defaultScale;

        // defaults: no ai + silent
        def.behavior.entityOptions.noAI = true;
        def.behavior.entityOptions.silent = true;

        def.interactions.putIfAbsent("LEFT", new ArrayList<>());
        def.interactions.putIfAbsent("RIGHT", new ArrayList<>());
        def.interactions.putIfAbsent("MIDDLE", new ArrayList<>());
        def.interactions.putIfAbsent("ANY", new ArrayList<>());

        common.npcs.put(def);

        if (common.npcManager != null) {
            common.npcManager.markDirty(def.name);
        }

        src.sendSystemMessage(Component.literal("§aCreated NPC §f" + def.name + "§a of type §f" + entityType + "§a."));
        System.out.println("[MysticEssentials] Created NPC " + def.name + " of type " + entityType + ".");

        return save(common, src, def);
    }

    private static int createPlayer(MysticEssentialsCommon common, CommandSourceStack src, String name, String profileName) throws CommandSyntaxException {
        ServerPlayer p = requirePlayer(src);
        if (common.npcs == null || common.cfg == null || common.cfg.npcs == null) {
            src.sendFailure(Component.literal("§cNPC system not ready."));
            return 0;
        }
        if (common.cfg.features != null && (!common.cfg.features.enableNpcSystem || !common.cfg.npcs.enabled)) {
            src.sendFailure(Component.literal("§cNPC system is disabled in config."));
            return 0;
        }
        if (common.npcs.get(name) != null) {
            src.sendFailure(Component.literal("§cAn NPC with that name already exists."));
            return 0;
        }

        NpcDefinition def = new NpcDefinition();
        def.name = name;
        System.out.println("Creating NPC (Player) with name " + name + " and profile " + profileName);
        def.enabled = true;

        setAnchorInFrontOfPlayer(def, p, 0.5D);

        def.type.kind = "PLAYER";
        def.type.playerProfile.name = profileName;

        // skin subsystem (placeholder for now; runtime will later fetch/cache)
        def.type.playerProfile.skin.source = "MOJANG_USER";
        def.type.playerProfile.skin.value = profileName;
        System.out.println("Set player profile name to " + profileName);

        def.display.scale = common.cfg.npcs.defaultScale;

        def.interactions.putIfAbsent("LEFT", new ArrayList<>());
        def.interactions.putIfAbsent("RIGHT", new ArrayList<>());
        def.interactions.putIfAbsent("MIDDLE", new ArrayList<>());
        def.interactions.putIfAbsent("ANY", new ArrayList<>());

        common.npcs.put(def);

        if (common.npcManager != null) {
            common.npcManager.markDirty(def.name);
        }

        src.sendSystemMessage(Component.literal("§aCreated NPC §f" + def.name + "§a of player profile §f" + profileName + "§a."));
        System.out.println("[MysticEssentials] Created NPC " + def.name + " of player profile " + profileName + ".");

        return save(common, src, def);
    }

    private static int delete(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;
        try {
            common.npcs.delete(def.name);
            // mark runtime dirty so NpcManager can despawn it on next tick
            if (common.npcManager != null) {
                common.npcManager.markDirty(def.name);
            }
        } catch (IOException e) {
            src.sendFailure(Component.literal("§cFailed to delete: " + e.getMessage()));
            return 0;
        }
        src.sendSystemMessage(Component.literal("§aDeleted NPC §f" + def.name + "§a."));
        return 1;
    }

    private static int moveHere(MysticEssentialsCommon common, CommandSourceStack src, String name) throws CommandSyntaxException {
        ServerPlayer p = requirePlayer(src);
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        setAnchorInFrontOfPlayer(def, p, 0.5D);

        src.sendSystemMessage(Component.literal("§aMoved NPC §f" + def.name + "§a."));

        if (common.npcManager != null) {
            common.npcManager.markDirty(def.name);
        }

        return save(common, src, def);
    }

    private static int centerNpc(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.anchor.x = center(def.anchor.x);
        def.anchor.z = center(def.anchor.z);

        src.sendSystemMessage(Component.literal("§aCentered NPC §f" + def.name + "§a."));

        if (common.npcManager != null) {
            common.npcManager.markDirty(def.name);
        }

        return save(common, src, def);
    }

    private static int tp(MysticEssentialsCommon common, CommandSourceStack src, String name) throws CommandSyntaxException {
        ServerPlayer p = requirePlayer(src);
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        String cmd = String.format(Locale.ROOT, "execute in %s run tp %s %.3f %.3f %.3f",
                def.anchor.dimension, p.getName().getString(), def.anchor.x, def.anchor.y, def.anchor.z);

        src.getServer().getCommands().performPrefixedCommand(src.getServer().createCommandSourceStack(), cmd);
        return 1;
    }

    private static int reload(MysticEssentialsCommon common, CommandSourceStack src) {
        if (common.npcs == null) {
            src.sendFailure(Component.literal("§cNPC store not initialized."));
            return 0;
        }
        try {
            common.npcs.reloadAll();
            // also refresh runtime NPCs if manager + server exist
            if (common.npcManager != null) {
                src.getServer();
                common.npcManager.reloadAll(src.getServer());
            }
        } catch (IOException e) {
            src.sendFailure(Component.literal("§cFailed to reload: " + e.getMessage()));
            return 0;
        }
        src.sendSystemMessage(Component.literal("§aReloaded NPCs."));
        return 1;
    }

    // ---------- action impl ----------
    private static int actionList(MysticEssentialsCommon common, CommandSourceStack src, String name, String clickRaw) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        String click = clickRaw.toUpperCase(Locale.ROOT);
        var list = def.interactions.get(click);
        if (list == null || list.isEmpty()) {
            src.sendSystemMessage(Component.literal("§eNo actions for §f" + def.name + "§e on §f" + click + "§e."));
            return 0;
        }

        src.sendSystemMessage(Component.literal("§eActions for §f" + def.name + "§e on §f" + click + "§e (" + list.size() + "):"));
        for (int i = 0; i < list.size(); i++) {
            src.sendSystemMessage(Component.literal(" §7" + (i + 1) + ". §f" + list.get(i)));
        }
        return list.size();
    }

    private static int actionAdd(MysticEssentialsCommon common, CommandSourceStack src, String name, String clickRaw, String cmd) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        String click = clickRaw.toUpperCase(Locale.ROOT);
        def.interactions.putIfAbsent(click, new ArrayList<>());
        def.interactions.get(click).add(cmd);

        src.sendSystemMessage(Component.literal("§aAdded action to §f" + def.name + "§a (" + click + ")."));
        return save(common, src, def);
    }

    private static int actionInsert(MysticEssentialsCommon common, CommandSourceStack src, String name, String clickRaw, int index1, String cmd) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        String click = clickRaw.toUpperCase(Locale.ROOT);
        def.interactions.putIfAbsent(click, new ArrayList<>());
        var list = def.interactions.get(click);

        int idx = index1 - 1;
        if (idx < 0 || idx > list.size()) {
            src.sendFailure(Component.literal("§cIndex out of range. Valid: 1-" + (list.size() + 1)));
            return 0;
        }

        list.add(idx, cmd);
        src.sendSystemMessage(Component.literal("§aInserted action #" + index1 + " on §f" + def.name + "§a (" + click + ")."));
        return save(common, src, def);
    }

    private static int actionSet(MysticEssentialsCommon common, CommandSourceStack src, String name, String clickRaw, int index1, String cmd) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        String click = clickRaw.toUpperCase(Locale.ROOT);
        var list = def.interactions.get(click);
        if (list == null || list.isEmpty()) {
            src.sendFailure(Component.literal("§cNo actions to set for that click type."));
            return 0;
        }

        int idx = index1 - 1;
        if (idx < 0 || idx >= list.size()) {
            src.sendFailure(Component.literal("§cIndex out of range. Valid: 1-" + list.size()));
            return 0;
        }

        list.set(idx, cmd);
        src.sendSystemMessage(Component.literal("§aSet action #" + index1 + " on §f" + def.name + "§a (" + click + ")."));
        return save(common, src, def);
    }

    private static int actionDel(MysticEssentialsCommon common, CommandSourceStack src, String name, String clickRaw, int index1) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        String click = clickRaw.toUpperCase(Locale.ROOT);
        var list = def.interactions.get(click);
        if (list == null || list.isEmpty()) {
            src.sendFailure(Component.literal("§cNo actions for that click type."));
            return 0;
        }

        int idx = index1 - 1;
        if (idx < 0 || idx >= list.size()) {
            src.sendFailure(Component.literal("§cIndex out of range. Valid: 1-" + list.size()));
            return 0;
        }

        list.remove(idx);
        src.sendSystemMessage(Component.literal("§aDeleted action #" + index1 + " on §f" + def.name + "§a (" + click + ")."));
        return save(common, src, def);
    }

    private static int actionClear(MysticEssentialsCommon common, CommandSourceStack src, String name, String clickRaw) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        String click = clickRaw.toUpperCase(Locale.ROOT);
        def.interactions.remove(click);

        src.sendSystemMessage(Component.literal("§aCleared actions for §f" + def.name + "§a (" + click + ")."));
        return save(common, src, def);
    }

    // ---------- settings impl ----------
    private static int setScale(MysticEssentialsCommon common, CommandSourceStack src, String name, float scale) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.display.scale = scale;
        src.sendSystemMessage(Component.literal("§aSet scale of §f" + def.name + " §ato §f" + scale + "§a."));

        if (common.npcManager != null) {
            common.npcManager.markDirty(def.name);
        }

        return save(common, src, def);
    }

    private static int setGlow(MysticEssentialsCommon common, CommandSourceStack src, String name, boolean enabled) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.visuals.glowing = enabled;
        src.sendSystemMessage(Component.literal("§aGlowing for §f" + def.name + "§a: " + (enabled ? "§aON" : "§cOFF")));

        if (common.npcManager != null) {
            common.npcManager.markDirty(def.name);
        }

        return save(common, src, def);
    }

    private static int setGlowColor(MysticEssentialsCommon common, CommandSourceStack src, String name, String color) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.visuals.glowingColor = color.toUpperCase(Locale.ROOT);
        src.sendSystemMessage(Component.literal("§aGlow color for §f" + def.name + "§a set to §f" + def.visuals.glowingColor + "§a."));

        if (common.npcManager != null) {
            common.npcManager.markDirty(def.name);
        }

        return save(common, src, def);
    }

    private static int setLook(MysticEssentialsCommon common, CommandSourceStack src, String name, boolean enabled) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.behavior.lookAtPlayer.enabled = enabled;
        src.sendSystemMessage(Component.literal("§aLookAtPlayer for §f" + def.name + "§a: " + (enabled ? "§aON" : "§cOFF")));

        if (common.npcManager != null) {
            common.npcManager.markDirty(def.name);
        }

        return save(common, src, def);
    }

    private static int setLookRange(MysticEssentialsCommon common, CommandSourceStack src, String name, int blocks) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.behavior.lookAtPlayer.rangeBlocks = blocks;
        src.sendSystemMessage(Component.literal("§aLook range for §f" + def.name + "§a set to §f" + blocks + "§a."));

        if (common.npcManager != null) {
            common.npcManager.markDirty(def.name);
        }

        return save(common, src, def);
    }

    private static int equipSetFromHand(MysticEssentialsCommon common, CommandSourceStack src, String name, String slotRaw, boolean useOffhand) throws CommandSyntaxException {
        ServerPlayer p = requirePlayer(src);
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        var slot = NpcDefinition.toEquipmentSlot(slotRaw);
        if (slot == null) {
            src.sendFailure(Component.literal("§cInvalid slot. Use MAINHAND/OFFHAND/HEAD/CHEST/LEGS/FEET."));
            return 0;
        }

        var held = useOffhand ? p.getOffhandItem() : p.getMainHandItem();
        if (held == null || held.isEmpty()) {
            src.sendFailure(Component.literal("§cYou must hold an item in your " + (useOffhand ? "offhand" : "main hand") + "."));
            return 0;
        }

        String key = slot.getName().toUpperCase(Locale.ROOT);
        def.equipment.items.put(key,
                com.alphine.mysticessentials.npc.util.NpcItemCodec.encode(held, src.getServer().registryAccess()));
        // default visibility: true
        def.equipment.setVisible(key, true);

        common.npcManager.markDirty(def.name);

        src.sendSystemMessage(Component.literal("§aEquipped §f" + def.name + "§a slot §f" + key + "§a."));
        return save(common, src, def);
    }

    private static int equipClear(MysticEssentialsCommon common, CommandSourceStack src, String name, String slotRaw) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        var slot = NpcDefinition.toEquipmentSlot(slotRaw);
        if (slot == null) {
            src.sendFailure(Component.literal("§cInvalid slot. Use MAINHAND/OFFHAND/HEAD/CHEST/LEGS/FEET."));
            return 0;
        }

        def.equipment.items.remove(slot.getName().toUpperCase(Locale.ROOT));

        common.npcManager.markDirty(def.name);

        src.sendSystemMessage(Component.literal("§aCleared equipment slot §f" + slot.getName().toUpperCase(Locale.ROOT) + "§a for §f" + def.name + "§a."));
        return save(common, src, def);
    }

    private static int equipList(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        src.sendSystemMessage(Component.literal("§eEquipment for §f" + def.name + "§e:"));
        if (def.equipment == null || def.equipment.items == null || def.equipment.items.isEmpty()) {
            src.sendSystemMessage(Component.literal(" §7- §f<empty>"));
            return 1;
        }

        for (var e : def.equipment.items.entrySet()) {
            var stack = com.alphine.mysticessentials.npc.util.NpcItemCodec.decode(e.getValue(), src.getServer().registryAccess());
            String itemName = stack.isEmpty() ? "§7<invalid/empty>" : "§f" + stack.getHoverName().getString();
            src.sendSystemMessage(Component.literal(" §7- §f" + e.getKey() + " §8= " + itemName));
        }
        return def.equipment.items.size();
    }

    private static int equipSetVisible(MysticEssentialsCommon common, CommandSourceStack src,
                                       String name, String slotRaw, boolean visible) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        var slot = NpcDefinition.toEquipmentSlot(slotRaw);
        if (slot == null) {
            src.sendFailure(Component.literal("§cInvalid slot. Use MAINHAND/OFFHAND/HEAD/CHEST/LEGS/FEET."));
            return 0;
        }

        String key = slot.getName().toUpperCase(Locale.ROOT);
        if (def.equipment == null || def.equipment.items == null || !def.equipment.items.containsKey(key)) {
            src.sendFailure(Component.literal("§cNo equipment stored for slot §f" + key + "§c."));
            return 0;
        }

        def.equipment.setVisible(key, visible);
        common.npcManager.markDirty(def.name);

        src.sendSystemMessage(Component.literal("§aSlot §f" + key + "§a for §f" + def.name +
                "§a is now " + (visible ? "§aVISIBLE" : "§cHIDDEN") + "§a."));
        return save(common, src, def);
    }

    private static int dialogueList(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        var lines = def.dialogue.lines;
        if (lines == null || lines.isEmpty()) {
            src.sendSystemMessage(Component.literal("§eNo dialogue lines for §f" + def.name + "§e."));
            return 0;
        }

        src.sendSystemMessage(Component.literal("§eDialogue for §f" + def.name + "§e (mode=" +
                def.dialogue.mode + ", enabled=" + def.dialogue.enabled + "):"));
        for (int i = 0; i < lines.size(); i++) {
            src.sendSystemMessage(Component.literal(" §7" + (i + 1) + ". §f" + lines.get(i)));
        }
        return lines.size();
    }

    private static int dialogueAdd(MysticEssentialsCommon common, CommandSourceStack src, String name, String line) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.dialogue.lines.add(line);
        src.sendSystemMessage(Component.literal("§aAdded dialogue line to §f" + def.name + "§a."));
        common.npcManager.markDirty(def.name);
        return save(common, src, def);
    }

    private static int dialogueSet(MysticEssentialsCommon common, CommandSourceStack src, String name, int index1, String line) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        int idx = index1 - 1;
        if (idx < 0 || idx >= def.dialogue.lines.size()) {
            src.sendFailure(Component.literal("§cIndex out of range. Valid: 1-" + def.dialogue.lines.size()));
            return 0;
        }

        def.dialogue.lines.set(idx, line);
        src.sendSystemMessage(Component.literal("§aUpdated dialogue line #" + index1 + " for §f" + def.name + "§a."));
        common.npcManager.markDirty(def.name);
        return save(common, src, def);
    }

    private static int dialogueDel(MysticEssentialsCommon common, CommandSourceStack src, String name, int index1) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        int idx = index1 - 1;
        if (idx < 0 || idx >= def.dialogue.lines.size()) {
            src.sendFailure(Component.literal("§cIndex out of range. Valid: 1-" + def.dialogue.lines.size()));
            return 0;
        }

        def.dialogue.lines.remove(idx);
        src.sendSystemMessage(Component.literal("§aDeleted dialogue line #" + index1 + " for §f" + def.name + "§a."));
        common.npcManager.markDirty(def.name);
        return save(common, src, def);
    }

    private static int dialogueClear(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.dialogue.lines.clear();
        src.sendSystemMessage(Component.literal("§aCleared dialogue for §f" + def.name + "§a."));
        common.npcManager.markDirty(def.name);
        return save(common, src, def);
    }

    private static int dialogueEnabled(MysticEssentialsCommon common, CommandSourceStack src, String name, boolean enabled) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.dialogue.enabled = enabled;
        src.sendSystemMessage(Component.literal("§aDialogue for §f" + def.name + "§a: " + (enabled ? "§aON" : "§cOFF")));
        common.npcManager.markDirty(def.name);
        return save(common, src, def);
    }

    private static int dialogueMode(MysticEssentialsCommon common, CommandSourceStack src, String name, String modeRaw) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        String mode = modeRaw.toUpperCase(Locale.ROOT);
        if (!mode.equals("SEQUENTIAL") && !mode.equals("RANDOM")) {
            src.sendFailure(Component.literal("§cInvalid mode. Use SEQUENTIAL or RANDOM."));
            return 0;
        }

        def.dialogue.mode = mode;
        src.sendSystemMessage(Component.literal("§aDialogue mode for §f" + def.name + "§a set to §f" + mode + "§a."));
        common.npcManager.markDirty(def.name);
        return save(common, src, def);
    }

    private static int pathAdd(MysticEssentialsCommon common, CommandSourceStack src, String name) throws CommandSyntaxException {
        ServerPlayer p = requirePlayer(src);
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        var wp = new NpcDefinition.Behavior.Path.Waypoint();
        wp.dimension = p.serverLevel().dimension().location().toString();
        wp.x = p.getX();
        wp.y = p.getY();
        wp.z = p.getZ();
        wp.yaw = p.getYRot();
        wp.pitch = p.getXRot();
        wp.waitTicks = 0;

        def.behavior.path.waypoints.add(wp);
        src.sendSystemMessage(Component.literal("§aAdded waypoint #" + def.behavior.path.waypoints.size()
                + " for §f" + def.name + "§a."));
        common.npcManager.markDirty(def.name);
        return save(common, src, def);
    }

    private static int pathClear(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.behavior.path.waypoints.clear();
        src.sendSystemMessage(Component.literal("§aCleared path for §f" + def.name + "§a."));
        common.npcManager.markDirty(def.name);
        return save(common, src, def);
    }

    private static int pathEnabled(MysticEssentialsCommon common, CommandSourceStack src, String name, boolean enabled) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.behavior.path.enabled = enabled;
        src.sendSystemMessage(Component.literal("§aPathing for §f" + def.name + "§a: " + (enabled ? "§aON" : "§cOFF")));
        common.npcManager.markDirty(def.name);
        return save(common, src, def);
    }

    private static int pathSpeed(MysticEssentialsCommon common, CommandSourceStack src, String name, float bps) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        def.behavior.path.speedBlocksPerSecond = bps;
        src.sendSystemMessage(Component.literal("§aSet path speed for §f" + def.name + "§a to §f" + bps + " blocks/s§a."));
        common.npcManager.markDirty(def.name);
        return save(common, src, def);
    }

    private static int pathList(MysticEssentialsCommon common, CommandSourceStack src, String name) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        var list = def.behavior.path.waypoints;
        if (list == null || list.isEmpty()) {
            src.sendSystemMessage(Component.literal("§eNo waypoints for §f" + def.name + "§e."));
            return 0;
        }

        src.sendSystemMessage(Component.literal("§eWaypoints for §f" + def.name + "§e:"));
        for (int i = 0; i < list.size(); i++) {
            var wp = list.get(i);
            src.sendSystemMessage(Component.literal(String.format(
                    " §7%d. §f%s §8(%.2f, %.2f, %.2f) wait=%dt",
                    i + 1,
                    wp.dimension,
                    wp.x, wp.y, wp.z,
                    wp.waitTicks
            )));
        }
        return list.size();
    }

    private static int skinSet(MysticEssentialsCommon common, CommandSourceStack src,
                               String name, String sourceRaw, String value) {
        NpcDefinition def = requireNpc(common, src, name);
        if (def == null) return 0;

        if (!"PLAYER".equalsIgnoreCase(def.type.kind)) {
            src.sendFailure(Component.literal("§cSkin settings only apply to PLAYER-type NPCs."));
            return 0;
        }

        String source = sourceRaw.toUpperCase(Locale.ROOT);
        def.type.playerProfile.skin.source = source;
        def.type.playerProfile.skin.value = value;

        src.sendSystemMessage(Component.literal("§aUpdated skin for §f" + def.name + "§a: source=" +
                source + " value=" + value));
        common.npcManager.markDirty(def.name);
        return save(common, src, def);
    }
}
