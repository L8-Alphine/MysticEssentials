package com.alphine.mysticessentials.neoforge.npc;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.npc.model.NpcDefinition;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(
        modid = MysticEssentialsCommon.MOD_ID,
        value = Dist.DEDICATED_SERVER
)
public class NpcInteractionEvents {

    private static MysticEssentialsCommon common() {
        return MysticEssentialsCommon.get();
    }

    @SubscribeEvent
    public static void onRightClickEntity(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getLevel().isClientSide()) return;

        MysticEssentialsCommon common = common();
        if (common == null || common.npcManager == null) return;

        Entity target = event.getTarget();
        NpcDefinition def = common.npcManager.findNpcByEntity(target);
        if (def == null || !def.enabled) return;

        // Sneak-right = MIDDLE; normal right = RIGHT
        String clickType = player.isShiftKeyDown() ? "MIDDLE" : "RIGHT";

        boolean ran = common.npcManager.triggerInteraction(player, target, def, clickType);
        boolean blockEquip = !def.behavior.entityOptions.allowEquipmentInteraction;

        if (ran || blockEquip) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        MysticEssentialsCommon common = common();
        if (common == null || common.npcManager == null) return;

        Entity target = event.getTarget();
        NpcDefinition def = common.npcManager.findNpcByEntity(target);
        if (def == null || !def.enabled) return;

        boolean ran = common.npcManager.triggerInteraction(player, target, def, "LEFT");
        boolean blockDamage = !def.behavior.entityOptions.allowDamage || ran;

        if (blockDamage) {
            event.setCanceled(true);
        }
    }
}
