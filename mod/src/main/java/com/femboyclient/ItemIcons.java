package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A small picture for an item by its name, for search results and the planner's lists: the wiki's own thumbnail when the page
 * has one (asked for in one batch with {@link #want}), else the game's own item for the material Hypixel lists for it (a
 * Hyperion is an iron sword), else nothing. Game thread only.
 */
final class ItemIcons {

	/** Hypixel's list still uses some 1.8 material names. */
	private static final Map<String, String> OLD_NAMES = Map.ofEntries(
			Map.entry("SKULL_ITEM", "player_head"), Map.entry("WOOD_SWORD", "wooden_sword"), Map.entry("GOLD_SWORD", "golden_sword"),
			Map.entry("WOOD_AXE", "wooden_axe"), Map.entry("GOLD_AXE", "golden_axe"), Map.entry("WOOD_PICKAXE", "wooden_pickaxe"),
			Map.entry("GOLD_PICKAXE", "golden_pickaxe"), Map.entry("WOOD_SPADE", "wooden_shovel"), Map.entry("GOLD_SPADE", "golden_shovel"),
			Map.entry("IRON_SPADE", "iron_shovel"), Map.entry("DIAMOND_SPADE", "diamond_shovel"), Map.entry("INK_SACK", "ink_sac"),
			Map.entry("GOLD_HELMET", "golden_helmet"), Map.entry("GOLD_CHESTPLATE", "golden_chestplate"),
			Map.entry("GOLD_LEGGINGS", "golden_leggings"), Map.entry("GOLD_BOOTS", "golden_boots"), Map.entry("RAW_FISH", "cod"),
			Map.entry("FIREWORK", "firework_rocket"), Map.entry("WATCH", "clock"), Map.entry("SULPHUR", "gunpowder"),
			Map.entry("EYE_OF_ENDER", "ender_eye"), Map.entry("NETHER_STALK", "nether_wart"), Map.entry("RED_ROSE", "poppy"),
			Map.entry("ENCHANTED_BOOK", "enchanted_book"), Map.entry("BOOK_AND_QUILL", "writable_book"));

	private static final Map<String, String> THUMBS = new HashMap<>();
	private static final Set<String> ASKED = new HashSet<>();
	private static final Map<String, ItemStack> STACKS = new HashMap<>();

	private ItemIcons() {
	}

	/** Asks the wiki, in one request, for the thumbnails of the names not asked about before. */
	static void want(List<String> names) {
		List<String> fresh = new ArrayList<>();
		for (String name : names) {
			if (name != null && ASKED.add(name)) {
				fresh.add(name);
			}
		}
		if (fresh.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		WikiPanel.client().thumbnails(fresh).whenComplete((found, failure) -> mc.execute(() -> {
			if (found != null) {
				THUMBS.putAll(found);
				found.values().forEach(WikiImages::request);
			}
		}));
	}

	/** Draws the item's picture in a {@code size} square. Returns false when there is none (yet). */
	static boolean draw(GuiGraphicsExtractor g, String name, int x, int y, int size) {
		String thumb = THUMBS.get(name);
		if (thumb != null && WikiImages.draw(g, thumb, x, y, size)) {
			return true;
		}
		ItemStack stack = STACKS.computeIfAbsent(name, ItemIcons::stackFor);
		if (stack.isEmpty()) {
			return false;
		}
		if (size == 16) {
			g.item(stack, x, y);
		} else {
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(size / 16f, size / 16f);
			g.item(stack, 0, 0);
			g.pose().popMatrix();
		}
		return true;
	}

	/** The game's item for the material Hypixel lists, or an empty stack. */
	private static ItemStack stackFor(String name) {
		ItemNames.Info info = WikiPanel.ITEM_NAMES.infoFor(name);
		if (info == null || info.material() == null) {
			return ItemStack.EMPTY;
		}
		String id = OLD_NAMES.getOrDefault(info.material(), info.material().toLowerCase(Locale.ROOT));
		Identifier key = Identifier.tryParse("minecraft:" + id);
		return key == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getOptional(key).filter(item -> item != Items.AIR)
				.map(ItemStack::new).orElse(ItemStack.EMPTY);
	}

	/** Forgets the material pictures (Hypixel's list arrived after some were looked up). */
	static void reset() {
		STACKS.clear();
	}
}
