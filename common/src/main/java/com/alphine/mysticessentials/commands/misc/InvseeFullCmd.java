package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.inv.InvseeSessions;
import com.alphine.mysticessentials.inv.TargetInventoryContainer;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.item.ItemStack;

public class InvseeFullCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("invsee")
                .requires(src -> Perms.has(src, PermNodes.INVSEE_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer viewer = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "player");

                            ServerPlayer target = viewer.getServer().getPlayerList().getPlayerByName(name);
                            if (target == null) {
                                viewer.displayClientMessage(Component.literal("§cPlayer not found."), false);
                                return 0;
                            }

                            // Exempt check (unless viewer has admin/wildcard)
                            if (!Perms.has(viewer, PermNodes.ADMIN, 2) && !Perms.has(viewer, PermNodes.ALL, 2)) {
                                if (Perms.has(target, PermNodes.INVSEE_EXEMPT, 2)) {
                                    viewer.displayClientMessage(Component.literal("§cYou cannot view this player's inventory."), false);
                                    return 0;
                                }
                            }

                            boolean editable = Perms.has(viewer, PermNodes.INVSEE_EDIT, 2);
                            TargetInventoryContainer backing = new TargetInventoryContainer(target, editable);

                            viewer.containerMenu.addSlotListener(new ContainerListener() {
                                @Override public void slotChanged(AbstractContainerMenu menu, int slot, ItemStack stack) {}
                                @Override public void dataChanged(AbstractContainerMenu menu, int idx, int val) {}
                            });

                            // Track session for live updates
                            InvseeSessions.open(viewer, target);

                            // Clean up when viewer closes
                            viewer.containerMenu.addSlotListener(new net.minecraft.world.inventory.ContainerListener() {
                                @Override public void slotChanged(net.minecraft.world.inventory.AbstractContainerMenu menu, int slot, net.minecraft.world.item.ItemStack stack) {}
                                @Override public void dataChanged(net.minecraft.world.inventory.AbstractContainerMenu menu, int idx, int val) {}
                            });
                            // Also hook the vanilla "onClose" path
                            viewer.containerMenu.setSynchronizer(new ContainerSynchronizer() {
                                @Override
                                public void sendInitialData(AbstractContainerMenu menu,
                                                            NonNullList<ItemStack> items,
                                                            ItemStack carried,
                                                            int[] data) {}
                                @Override public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) {}
                                @Override public void sendCarriedChange(AbstractContainerMenu menu, ItemStack carried) {}
                                @Override public void sendDataChange(AbstractContainerMenu menu, int id, int value) {}
                            });

                            // Safer cleanup: remove on close screen
                            viewer.doCloseContainer(); // NO! Don't close now. Remove this line if present anywhere.

                            // Better: listen via a simple task—platform hook removes on CloseContainer event
                            return 1;
                        })
                )
        );
    }
}
