package org.hyzionstudios.mysticessentials.modules.lootbox.hytale;

import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The {@code MysticCrate} block interaction. Shipped crate blocks declare it as
 * their Use so {@code UseBlockEvent.Pre} fires for them:
 *
 * <pre>{@code
 * "Interactions": { "Use": { "Interactions": [ { "Type": "MysticCrate" } ] } }
 * }</pre>
 *
 * <p>On a block bound to a pool the crate system cancels the Pre event and opens
 * the preview, so this never runs. It only runs on a crate block nobody bound
 * yet, and tells the player so (staff get the command to bind it).</p>
 */
public final class CrateInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "MysticCrate";

    public static final BuilderCodec<CrateInteraction> CODEC = BuilderCodec
            .builder(CrateInteraction.class, CrateInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Mystic Essentials LootBox crate. A block bound with /lootbox crate convert opens its "
                    + "pool's preview on Use; an unbound block explains how to bind it.")
            .build();

    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        LootBoxModule module = LootBoxModule.active();
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> ref = context.getEntity();
        if (module == null || commandBuffer == null || ref == null || type != InteractionType.Use) {
            return;
        }
        PlayerRef player = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (player != null) {
            module.onUnboundCrateUse(player);
        }
    }
}
