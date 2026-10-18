package org.hyzionstudios.mysticessentials.spikes;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The {@code Use} interaction generated block variants carry (S2). It only
 * reports that it ran: when the probe cancels {@code UseBlockEvent.Pre} for a
 * swapped block, as the real crate system will, this never executes.
 */
public final class SpikeUseInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "LBSpikeUse";

    public static final BuilderCodec<SpikeUseInteraction> CODEC = BuilderCodec
            .builder(SpikeUseInteraction.class, SpikeUseInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("DEV ONLY: LootBox spike S2 marker. Reports that a generated variant's Use ran.")
            .build();

    static volatile SpikeLog log;

    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        BlockPosition block = context.getTargetBlock();
        String where = block == null ? "?" : block.x + "," + block.y + "," + block.z;
        if (log != null) {
            log.line("S2", "LBSpikeUse ran (" + type + ") at " + where
                    + ": the variant's root interaction resolved and executed.");
        }
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        Ref<EntityStore> ref = context.getEntity();
        PlayerRef player = buffer == null || ref == null ? null
                : buffer.getComponent(ref, PlayerRef.getComponentType());
        if (player != null) {
            player.sendMessage(Message.raw("[LBSpike S2] Variant Use interaction ran at " + where));
        }
    }
}
