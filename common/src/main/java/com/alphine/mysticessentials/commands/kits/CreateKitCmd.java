package com.alphine.mysticessentials.commands.kits;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.KitStore;
import com.alphine.mysticessentials.util.DurationUtil;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

public class CreateKitCmd {
    private final KitStore kits;
    public CreateKitCmd(KitStore kits){ this.kits = kits; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("createkit")
                .requires(src -> Perms.has(src, PermNodes.KIT_CREATE, 2))
                .then(Commands.argument("kit", StringArgumentType.word())
                        .then(Commands.argument("time", StringArgumentType.word()) // -1 | 0/none | 1h30m ...
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    var provider = p.server.registryAccess();

                                    String name = StringArgumentType.getString(ctx, "kit");
                                    String timeArg = StringArgumentType.getString(ctx, "time").toLowerCase();

                                    long cooldown = 0;
                                    boolean oneTime = false;
                                    if (timeArg.equals("-1")) {
                                        oneTime = true;
                                    } else if (timeArg.equals("0") || timeArg.equals("none") || timeArg.equals("off")) {
                                        cooldown = 0;
                                    } else {
                                        cooldown = DurationUtil.parseToMillis(timeArg);
                                        if (cooldown < 0) cooldown = 0;
                                    }

                                    var kit = new KitStore.Kit();
                                    kit.name = name;
                                    kit.cooldownMillis = cooldown;
                                    kit.oneTime = oneTime;

                                    var inv = p.getInventory();
                                    for (int i = 0; i < 36; i++) {
                                        ItemStack st = inv.getItem(i);
                                        if (!st.isEmpty()) kit.itemsB64.add(KitStore.stackToB64(st, provider));
                                    }
                                    for (int i = 0; i < 4; i++) {
                                        ItemStack st = inv.armor.get(i);
                                        if (!st.isEmpty()) kit.itemsB64.add(KitStore.stackToB64(st, provider));
                                    }
                                    if (!inv.offhand.get(0).isEmpty()) {
                                        kit.itemsB64.add(KitStore.stackToB64(inv.offhand.getFirst(), provider));
                                    }

                                    kits.put(kit);

                                    if (oneTime) {
                                        p.displayClientMessage(MessagesUtil.msg("kit.create.saved_one_time",
                                                Map.of("kit", name)), false);
                                    } else if (cooldown > 0) {
                                        p.displayClientMessage(MessagesUtil.msg("kit.create.saved_cooldown",
                                                Map.of("kit", name, "time", DurationUtil.fmtRemaining(cooldown))), false);
                                    } else {
                                        p.displayClientMessage(MessagesUtil.msg("kit.create.saved_no_cooldown",
                                                Map.of("kit", name)), false);
                                    }
                                    p.displayClientMessage(MessagesUtil.msg("kit.create.permission",
                                            Map.of("perm", PermNodes.kitNode(name))), false);
                                    return 1;
                                })
                        )
                )
        );
    }
}
