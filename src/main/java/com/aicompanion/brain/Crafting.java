package com.aicompanion.brain;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The companion crafts the basic stuff a player crafts early on:
 * planks, sticks, a crafting table, wooden/stone/iron tools and torches.
 * (It does the crafting "in its head", one recipe every couple of seconds.)
 */
final class Crafting {
	private static final Map<Item, Item> LOG_TO_PLANKS = new LinkedHashMap<>();

	static {
		LOG_TO_PLANKS.put(Items.OAK_LOG, Items.OAK_PLANKS);
		LOG_TO_PLANKS.put(Items.SPRUCE_LOG, Items.SPRUCE_PLANKS);
		LOG_TO_PLANKS.put(Items.BIRCH_LOG, Items.BIRCH_PLANKS);
		LOG_TO_PLANKS.put(Items.JUNGLE_LOG, Items.JUNGLE_PLANKS);
		LOG_TO_PLANKS.put(Items.ACACIA_LOG, Items.ACACIA_PLANKS);
		LOG_TO_PLANKS.put(Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS);
		LOG_TO_PLANKS.put(Items.MANGROVE_LOG, Items.MANGROVE_PLANKS);
		LOG_TO_PLANKS.put(Items.CHERRY_LOG, Items.CHERRY_PLANKS);
		LOG_TO_PLANKS.put(Items.CRIMSON_STEM, Items.CRIMSON_PLANKS);
		LOG_TO_PLANKS.put(Items.WARPED_STEM, Items.WARPED_PLANKS);
	}

	private static final Predicate<ItemStack> COBBLE = s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.BLACKSTONE);
	private static final Predicate<ItemStack> FUEL = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);

	private Crafting() {
	}

	/** Crafts at most one thing per call. */
	static void tick(CompanionBrain brain) {
		Actions a = brain.actions();
		int logs = a.count(s -> s.is(ItemTags.LOGS));
		int planks = a.count(s -> s.is(ItemTags.PLANKS));
		int sticks = a.count(Items.STICK);
		int cobble = a.count(COBBLE);
		int iron = a.count(Items.IRON_INGOT);
		boolean hasTable = a.count(Items.CRAFTING_TABLE) > 0;
		int pickTier = a.bestPickaxeTier();
		int swordTier = bestTier(a, ItemTags.SWORDS);
		int axeTier = bestTier(a, ItemTags.AXES);

		boolean wantsTools = pickTier < 2 || swordTier < 2 || axeTier == 0 || (iron >= 3 && pickTier < 3);

		// logs -> planks (keep a small stack of planks around)
		if (logs > 0 && planks < 12) {
			int slot = a.findSlot(s -> s.is(ItemTags.LOGS));
			if (slot >= 0) {
				Item log = brain.body().getInventory().getItem(slot).getItem();
				Item plank = LOG_TO_PLANKS.getOrDefault(log, Items.OAK_PLANKS);
				a.remove(s -> s.is(log), 1);
				a.give(new ItemStack(plank, 4));
				return;
			}
		}
		// planks -> sticks
		if (sticks < 4 && planks >= 2 && (wantsTools || a.count(FUEL) > 0)) {
			a.remove(s -> s.is(ItemTags.PLANKS), 2);
			a.give(new ItemStack(Items.STICK, 4));
			return;
		}
		// crafting table first, like a real player
		if (!hasTable && wantsTools && planks >= 4) {
			a.remove(s -> s.is(ItemTags.PLANKS), 4);
			a.give(new ItemStack(Items.CRAFTING_TABLE));
			brain.announceCraft("a crafting table");
			return;
		}
		if (hasTable) {
			// pickaxes
			if (pickTier < 3 && iron >= 3 && sticks >= 2) {
				craft(a, s -> s.is(Items.IRON_INGOT), 3, 2, Items.IRON_PICKAXE);
				brain.announceCraft("an iron pickaxe");
				return;
			}
			if (pickTier < 2 && cobble >= 3 && sticks >= 2) {
				craft(a, COBBLE, 3, 2, Items.STONE_PICKAXE);
				brain.announceCraft("a stone pickaxe");
				return;
			}
			if (pickTier == 0 && planks >= 3 && sticks >= 2) {
				craft(a, s -> s.is(ItemTags.PLANKS), 3, 2, Items.WOODEN_PICKAXE);
				brain.announceCraft("a wooden pickaxe");
				return;
			}
			// swords
			if (swordTier < 3 && iron >= 2 && sticks >= 1 && pickTier >= 3) {
				craft(a, s -> s.is(Items.IRON_INGOT), 2, 1, Items.IRON_SWORD);
				brain.announceCraft("an iron sword");
				return;
			}
			if (swordTier < 2 && cobble >= 2 && sticks >= 1) {
				craft(a, COBBLE, 2, 1, Items.STONE_SWORD);
				brain.announceCraft("a stone sword");
				return;
			}
			if (swordTier == 0 && planks >= 2 && sticks >= 1) {
				craft(a, s -> s.is(ItemTags.PLANKS), 2, 1, Items.WOODEN_SWORD);
				brain.announceCraft("a wooden sword");
				return;
			}
			// axes (chop trees faster)
			if (axeTier < 2 && cobble >= 3 && sticks >= 2) {
				craft(a, COBBLE, 3, 2, Items.STONE_AXE);
				brain.announceCraft("a stone axe");
				return;
			}
			if (axeTier == 0 && planks >= 3 && sticks >= 2) {
				craft(a, s -> s.is(ItemTags.PLANKS), 3, 2, Items.WOODEN_AXE);
				brain.announceCraft("a wooden axe");
				return;
			}
		}
		// torches for mining
		if (a.count(Items.TORCH) < 16 && a.count(FUEL) > 0 && sticks >= 1) {
			a.remove(FUEL, 1);
			a.remove(s -> s.is(Items.STICK), 1);
			a.give(new ItemStack(Items.TORCH, 4));
		}
	}

	private static void craft(Actions a, Predicate<ItemStack> material, int materialCount, int stickCount, Item result) {
		a.remove(material, materialCount);
		a.remove(s -> s.is(Items.STICK), stickCount);
		a.give(new ItemStack(result));
	}

	private static int bestTier(Actions a, net.minecraft.tags.TagKey<Item> tag) {
		int best = 0;
		var inv = a.bot().getInventory();
		for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && s.is(tag)) {
				best = Math.max(best, Actions.tierOf(s));
			}
		}
		return best;
	}
}
