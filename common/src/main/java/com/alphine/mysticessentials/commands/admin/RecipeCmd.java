package com.alphine.mysticessentials.commands.admin;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.*;

public class RecipeCmd {

    private static final SuggestionProvider<CommandSourceStack> RECIPE_SUGGESTER = (ctx, builder) -> {
        RecipeManager rm = ctx.getSource().getServer().getRecipeManager();
        rm.getRecipeIds().forEach(rl -> builder.suggest(rl.toString())); // Stream<ResourceLocation>
        return builder.buildFuture();
    };

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("recipe")
                .requires(src -> Perms.has(src, PermNodes.RECIPE_USE, 0))

                // /recipe -> held item
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ItemStack held = p.getMainHandItem();
                    if (held.isEmpty()) {
                        p.displayClientMessage(MessagesUtil.msg("recipe.held_none"), false);
                        return 0;
                    }
                    return openRecipesForItem(p, held);
                })

                // /recipe <id>
                .then(Commands.argument("id", ResourceLocationArgument.id())
                        .suggests(RECIPE_SUGGESTER)
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");

                            Optional<? extends RecipeHolder<?>> holder = p.server.getRecipeManager().byKey(id);
                            if (holder.isEmpty()) {
                                p.displayClientMessage(MessagesUtil.msg("recipe.none", Map.of("item", id.toString())), false);
                                return 0;
                            }

                            // unlock in recipe book
                            p.awardRecipes(List.of(holder.get()));
                            p.displayClientMessage(MessagesUtil.msg("recipe.found", Map.of("item", id.toString())), false);
                            p.displayClientMessage(MessagesUtil.msg("recipe.viewer_hint"), false);
                            return 1;
                        })
                )
        );
    }

    private int openRecipesForItem(ServerPlayer p, ItemStack stack) {
        RecipeManager rm = p.server.getRecipeManager();

        // collect holders that craft the same item as in hand
        List<RecipeHolder<?>> matches = new ArrayList<>();
        for (RecipeHolder<?> h : rm.getRecipes()) {
            var r = h.value();
            var out = r.getResultItem(p.level().registryAccess());
            if (!out.isEmpty() && out.is(stack.getItem())) {
                matches.add(h);
            }
        }

        if (matches.isEmpty()) {
            p.displayClientMessage(MessagesUtil.msg("recipe.none", Map.of("item", stack.getHoverName().getString())), false);
            return 0;
        }

        p.awardRecipes(matches);

        Map<String, Object> ph = new HashMap<>();
        ph.put("count", matches.size());
        ph.put("item", stack.getHoverName().getString());
        p.displayClientMessage(MessagesUtil.msg("recipe.multiple", ph), false);
        p.displayClientMessage(MessagesUtil.msg("recipe.viewer_hint"), false);
        return 1;
    }
}
