package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Map;

public class EnchantCmd {

    private static final SuggestionProvider<CommandSourceStack> ENCH_SUGGEST = (ctx, b) -> {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        ItemStack stack = p.getMainHandItem();
        var reg = ctx.getSource().registryAccess().registryOrThrow(Registries.ENCHANTMENT);

        for (Holder.Reference<Enchantment> h : reg.holders().toList()) {
            boolean ok = stack.is(Items.ENCHANTED_BOOK) || h.value().canEnchant(stack);
            if (ok) b.suggest(h.key().location().toString());
        }
        return b.buildFuture();
    };

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("enchant")
                .requires(src -> Perms.has(src, PermNodes.ENCHANT_USE, 2))
                .then(Commands.argument("enchantment", ResourceLocationArgument.id())
                        .suggests(ENCH_SUGGEST)
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 255))
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    ItemStack stack = p.getMainHandItem();
                                    if (stack.isEmpty()) {
                                        p.displayClientMessage(MessagesUtil.msg("enchant.hold_item"), false);
                                        return 0;
                                    }

                                    ResourceLocation rl = ResourceLocationArgument.getId(ctx, "enchantment");
                                    var reg = p.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
                                    var optHolder = reg.getHolder(ResourceKey.create(Registries.ENCHANTMENT, rl));
                                    if (optHolder.isEmpty()) {
                                        p.displayClientMessage(MessagesUtil.msg("enchant.unknown", Map.of("id", rl.toString())), false);
                                        return 0;
                                    }
                                    Holder.Reference<Enchantment> ref = optHolder.get();

                                    int level = IntegerArgumentType.getInteger(ctx, "level");
                                    boolean book = stack.is(Items.ENCHANTED_BOOK);
                                    if (!book && !ref.value().canEnchant(stack)) {
                                        p.displayClientMessage(MessagesUtil.msg("enchant.incompatible"), false);
                                        return 0;
                                    }

                                    var compType = book ? DataComponents.STORED_ENCHANTMENTS : DataComponents.ENCHANTMENTS;

                                    ItemEnchantments current = stack.getOrDefault(compType, ItemEnchantments.EMPTY);
                                    ItemEnchantments.Mutable mut = new ItemEnchantments.Mutable(current);

                                    if (level <= 0) {
                                        mut.set(ref, 0);
                                        ItemEnchantments after = mut.toImmutable();
                                        if (after.isEmpty()) stack.remove(compType); else stack.set(compType, after);
                                        p.displayClientMessage(MessagesUtil.msg("enchant.removed", Map.of("id", rl.toString())), false);
                                    } else {
                                        mut.set(ref, level);
                                        stack.set(compType, mut.toImmutable());
                                        p.displayClientMessage(MessagesUtil.msg("enchant.applied",
                                                Map.of("id", rl.toString(), "level", level)), false);
                                    }

                                    return 1;
                                })
                        )
                )
        );
    }
}
