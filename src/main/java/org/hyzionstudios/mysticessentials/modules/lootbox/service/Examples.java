package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.List;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;

/**
 * The drafts written on first enable, so operators start from a working pool
 * instead of a blank file. Every item id here is a real vanilla id.
 */
final class Examples {

    private Examples() {
    }

    static LootBoxDraft pool() {
        LootBoxDraft draft = new LootBoxDraft();
        draft.id = "example_supply";
        draft.displayName = "<#E7B84D>Example Supply Crate";
        draft.description = "A starter pool. Edit this file, then run /lootbox publish example_supply.";
        draft.icon = "Furniture_Crude_Chest_Large";
        draft.mode = "WEIGHTED";
        draft.keys = List.of("mystic:example_key");
        draft.rewards.add(item("copper_pickaxe", "Copper Pickaxe", "Common", 4500, "Tool_Pickaxe_Copper", 1));
        draft.rewards.add(item("apples", "Apples x16", "Common", 3000, "Plant_Fruit_Apple", 16));
        draft.rewards.add(item("iron_bars", "Iron Bars x8", "Uncommon", 1500, "Ingredient_Bar_Iron", 8));
        draft.rewards.add(item("iron_sword", "Iron Sword", "Rare", 900, "Weapon_Sword_Iron", 1));
        LootBoxDraft.RewardSpec gold = item("gold_bars", "<#E8A93B>Gold Bars x4", "Legendary", 100,
                "Ingredient_Bar_Gold", 4);
        gold.announce = true;
        draft.rewards.add(gold);
        return draft;
    }

    static KeyDraft key() {
        KeyDraft key = new KeyDraft();
        key.id = "mystic:example_key";
        key.baseItemId = "Ingredient_Bar_Copper";
        key.name = "<#E7B84D>&lExample Crate Key";
        key.lore = List.of("&7Opens: &fExample Supply Crate", "&8Use it on the crate, or run /lootbox.");
        key.uses = 1;
        return key;
    }

    private static LootBoxDraft.RewardSpec item(String id, String name, String rarity, long weight, String itemId,
            int quantity) {
        LootBoxDraft.RewardSpec reward = new LootBoxDraft.RewardSpec();
        reward.id = id;
        reward.name = name;
        reward.rarity = rarity;
        reward.type = "ITEM";
        reward.weight = weight;
        reward.item = new LootBoxDraft.ItemSpec();
        reward.item.itemId = itemId;
        reward.item.quantity = quantity;
        return reward;
    }
}
