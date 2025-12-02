package com.alphine.mysticessentials.util;

import com.google.gson.Gson;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.core.HolderLookup;

public final class AdventureComponentBridge {

    private static final Gson GSON = GsonComponentSerializer.gson().serializer();

    private AdventureComponentBridge() {}

    public static net.minecraft.network.chat.Component advToNative(
            Object adv,
            HolderLookup.Provider registries
    ) {
        if (adv == null) {
            return net.minecraft.network.chat.Component.empty();
        }

        // Make absolutely sure we are casting to KYORI, not vanilla
        if (!(adv instanceof net.kyori.adventure.text.Component adventure)) {
            // Either fail gracefully or throw; I'd log + return empty
            return net.minecraft.network.chat.Component.empty();
        }

        String json = GSON.toJson(adventure);
        return net.minecraft.network.chat.Component.Serializer.fromJson(json, registries);
    }
}
