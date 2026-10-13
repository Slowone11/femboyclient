package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How many of each item the player carries, by item name (any case), for the recipe planner's "you have 5/8". Items are named
 * the way {@link WikiBar#pageFor} names them, so a reforged or starred item still counts. Recounted at most twice a second.
 * Game thread only.
 */
final class InventoryCounts {

	private static final long REFRESH_NS = 500_000_000L;

	private static Map<String, Integer> counts = Map.of();
	private static long countedAt;

	private InventoryCounts() {
	}

	/** How many of {@code name} are in the player's inventory. */
	static int count(String name) {
		refresh();
		return counts.getOrDefault(name.toLowerCase(Locale.ROOT), 0);
	}

	private static void refresh() {
		long now = System.nanoTime();
		if (now - countedAt < REFRESH_NS) {
			return;
		}
		countedAt = now;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			counts = Map.of();
			return;
		}
		Inventory inventory = mc.player.getInventory();
		Map<String, Integer> fresh = new HashMap<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) {
				fresh.merge(WikiBar.pageFor(stack).toLowerCase(Locale.ROOT), stack.getCount(), Integer::sum);
			}
		}
		counts = fresh;
	}
}
