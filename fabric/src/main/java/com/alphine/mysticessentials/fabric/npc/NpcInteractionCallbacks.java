package com.alphine.mysticessentials.fabric.npc;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.npc.model.NpcDefinition;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

public final class NpcInteractionCallbacks {

    private NpcInteractionCallbacks() {}

    public static void register(MysticEssentialsCommon common) {
        // RIGHT click
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            // Mojmap: isClientSide()
            if (world.isClientSide()) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (common.npcManager == null) return InteractionResult.PASS;

            NpcDefinition def = common.npcManager.findNpcByEntity(entity);
            if (def == null || !def.enabled) return InteractionResult.PASS;

            // sneak-right = MIDDLE, normal right = RIGHT
            String clickType = sp.isShiftKeyDown() ? "MIDDLE" : "RIGHT";

            boolean ran = common.npcManager.triggerInteraction(sp, entity, def, clickType);
            boolean blockEquip = !def.behavior.entityOptions.allowEquipmentInteraction;

            if (ran || blockEquip) {
                // consume interaction (no default behaviour)
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });

        // LEFT click (attack)
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (common.npcManager == null) return InteractionResult.PASS;

            NpcDefinition def = common.npcManager.findNpcByEntity(entity);
            if (def == null || !def.enabled) return InteractionResult.PASS;

            boolean ran = common.npcManager.triggerInteraction(sp, entity, def, "LEFT");
            boolean blockDamage = !def.behavior.entityOptions.allowDamage || ran;

            if (blockDamage) {
                // cancel damage
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }
}
