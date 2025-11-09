package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.inv.InvseeSessions;
import com.alphine.mysticessentials.inv.InventoryIO;
import com.alphine.mysticessentials.inv.SnapshotInventoryContainer;
import com.alphine.mysticessentials.inv.TargetInventoryContainer;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InvseeFullCmd {

    private final PlayerDataStore pdata;

    public InvseeFullCmd(PlayerDataStore pdata){
        this.pdata = pdata;
    }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("invsee")
                .requires(src -> Perms.has(src, PermNodes.INVSEE_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer viewer = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "player");

                            // Try ONLINE first
                            ServerPlayer target = viewer.getServer().getPlayerList().getPlayerByName(name);
                            if (target != null) {
                                // Exempt check (unless viewer has admin/wildcard)
                                if (!Perms.has(viewer, PermNodes.ADMIN, 2) && !Perms.has(viewer, PermNodes.ALL, 2)) {
                                    if (Perms.has(target, PermNodes.INVSEE_EXEMPT, 2)) {
                                        viewer.displayClientMessage(MessagesUtil.msg("invsee.exempt"), false);
                                        return 0;
                                    }
                                }

                                boolean editable = Perms.has(viewer, PermNodes.INVSEE_EDIT, 2);
                                TargetInventoryContainer backing = new TargetInventoryContainer(target, editable);

                                Component title = Component.translatable(
                                        editable ? "container.invsee.edit" : "container.invsee.view"
                                ).append(Component.literal(" - " + target.getGameProfile().getName()));

                                // IMPORTANT: pass the backing container directly (no wrappers)
                                viewer.openMenu(new SimpleMenuProvider(
                                        (id, playerInv, p) -> new ChestMenu(MenuType.GENERIC_9x6, id, playerInv, ensureRows(backing, 6), 6),
                                        title
                                ));

                                InvseeSessions.open(viewer, target);
                                viewer.displayClientMessage(
                                        MessagesUtil.msg(editable ? "invsee.open.edit" : "invsee.open.view",
                                                Map.of("player", target.getName().getString())),
                                        false
                                );
                                return 1;
                            }

                            // ---- OFFLINE fallback (snapshot) ----
                            Optional<GameProfile> prof = viewer.getServer().getProfileCache().get(name);
                            if (prof.isEmpty()) {
                                viewer.displayClientMessage(MessagesUtil.msg("tp.player_not_found"), false);
                                return 0;
                            }
                            UUID targetId = prof.get().getId();

                            var invData = pdata.getInventory(targetId);
                            if (invData == null || invData.payload == null || invData.payload.isEmpty()) {
                                viewer.displayClientMessage(MessagesUtil.msg("invsee.no_snapshot"), false);
                                return 0;
                            }

                            HolderLookup.Provider provider = viewer.level().registryAccess();
                            boolean editableOffline = Perms.has(viewer, PermNodes.INVSEE_EDIT_OFFLINE, 2);

                            ItemStack[] stacks = InventoryIO.stacksFromPayload(provider, invData.payload);

                            // Persist back into PlayerDataStore if editable
                            var onSave = editableOffline ? (java.util.function.Consumer<ItemStack[]>) arr -> {
                                var newPayload = InventoryIO.payloadFromStacks(provider, arr);
                                String fmt = invData.format == null ? "nbt-json" : invData.format;
                                pdata.saveInventory(targetId, fmt, newPayload, "invsee-offline-edit");
                            } : null;

                            SnapshotInventoryContainer snap = new SnapshotInventoryContainer(stacks, editableOffline, onSave);

                            Component title = Component.translatable(
                                    editableOffline ? "container.invsee.offline.edit" : "container.invsee.offline.view"
                            ).append(Component.literal(" - " + name));

                            viewer.openMenu(new SimpleMenuProvider(
                                    (id, playerInv, p) -> new ChestMenu(MenuType.GENERIC_9x6, id, playerInv, ensureRows(snap, 6), 6),
                                    title
                            ));

                            viewer.displayClientMessage(
                                    MessagesUtil.msg(
                                            editableOffline ? "invsee.open.offline.edit" : "invsee.open.offline.view",
                                            Map.of("player", name)
                                    ),
                                    false
                            );
                            return 1;
                        })
                )
        );
    }

    /**
     * ChestMenu requires a Container sized to rows*9. Our containers expose size 54 already,
     * but this helper lets you adapt anything smaller by copying into a SimpleContainer.
     * (Currently, TargetInventoryContainer/SnapshotInventoryContainer both return 54.)
     */
    private static Container ensureRows(Container c, int rows){
        int needed = rows * 9;
        if (c.getContainerSize() == needed) return c;
        SimpleContainer box = new SimpleContainer(needed);
        for (int i = 0; i < needed; i++) {
            box.setItem(i, i < c.getContainerSize() ? c.getItem(i).copy() : ItemStack.EMPTY);
        }
        return box;
    }
}
