package com.alphine.mysticessentials.commands;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class SharedSuggest {
    private SharedSuggest() {}

    public static CompletableFuture<Suggestions> hologramNames(MysticEssentialsCommon common, SuggestionsBuilder b) {
        if (common == null || common.holograms == null) return b.buildFuture();
        String rem = b.getRemaining().toLowerCase(Locale.ROOT);
        for (var h : common.holograms.list()) {
            String n = h.name; // <-- assumes `name`
            if (n != null && n.toLowerCase(Locale.ROOT).startsWith(rem)) b.suggest(n);
        }
        return b.buildFuture();
    }

    public static CompletableFuture<Suggestions> npcNames(MysticEssentialsCommon common, SuggestionsBuilder b) {
        if (common == null || common.npcs == null) return b.buildFuture();
        String rem = b.getRemaining().toLowerCase(Locale.ROOT);
        for (var n : common.npcs.list()) {
            String name = n.name; // <-- assumes `name`
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(rem)) b.suggest(name);
        }
        return b.buildFuture();
    }

    public static CompletableFuture<Suggestions> clickTypes(SuggestionsBuilder b) {
        b.suggest("LEFT");
        b.suggest("RIGHT");
        b.suggest("MIDDLE");
        b.suggest("ANY");
        return b.buildFuture();
    }

    public static CompletableFuture<Suggestions> billboardModes(SuggestionsBuilder b) {
        b.suggest("FIXED");
        b.suggest("VERTICAL");
        b.suggest("HORIZONTAL");
        b.suggest("CENTER");
        return b.buildFuture();
    }

    public static CompletableFuture<Suggestions> alignModes(SuggestionsBuilder b) {
        b.suggest("LEFT");
        b.suggest("CENTER");
        b.suggest("RIGHT");
        return b.buildFuture();
    }

    public static CompletableFuture<Suggestions> skinSources(SuggestionsBuilder b) {
        b.suggest("MIRROR_VIEWER");
        b.suggest("ONLINE_PLAYER");
        b.suggest("MOJANG_USER");
        b.suggest("URL");
        return b.buildFuture();
    }

    public static SuggestionProvider<CommandSourceStack> equipSlots() {
        return (ctx, b) -> {
            b.suggest("MAINHAND");
            b.suggest("OFFHAND");
            b.suggest("HEAD");
            b.suggest("CHEST");
            b.suggest("LEGS");
            b.suggest("FEET");
            return b.buildFuture();
        };
    }

    public static CompletableFuture<Suggestions> entityTypes(SuggestionsBuilder b) {
        String rem = b.getRemaining().toLowerCase(Locale.ROOT);

        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            String s = id.toString(); // e.g. "minecraft:villager"
            if (s.toLowerCase(Locale.ROOT).startsWith(rem)) {
                b.suggest(s);
            }
        }
        return b.buildFuture();
    }
}
