package com.femboyclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every SkyBlock item name, from Hypixel's public item list (no API key needed), for better autocomplete than the wiki's own:
 * "hyp" finds Hyperion. Matching is forgiving: the whole name, then a word of it, then the words' first letters. Kept free of
 * Minecraft classes so it can be tested on its own.
 */
public final class ItemNames {

	public static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";

	private volatile List<String> names = List.of();
	private volatile Map<String, String> byId = Map.of();
	private volatile java.util.Set<String> lowerNames = java.util.Set.of();
	private volatile Map<String, Info> info = Map.of();

	/** What Hypixel's list says about an item: its rarity ("LEGENDARY", or null) and its Minecraft material ("IRON_SWORD"). */
	public record Info(String tier, String material) {
	}

	/** Rarity and material by item name (any case). */
	public void setInfo(Map<String, Info> info) {
		this.info = Map.copyOf(info);
	}

	/** Rarity and material of an item, or null when Hypixel's list does not have it. */
	public Info infoFor(String name) {
		return name == null ? null : info.get(name.toLowerCase(Locale.ROOT));
	}

	/** Rarity and material by lower-case name, from Hypixel's answer. */
	public static Map<String, Info> parseInfo(String json) {
		Map<String, Info> all = new java.util.HashMap<>();
		try {
			for (JsonElement item : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("items")) {
				JsonObject object = item.getAsJsonObject();
				if (object.has("name")) {
					String name = object.get("name").getAsString().replaceAll("§.", "").strip().toLowerCase(Locale.ROOT);
					String tier = object.has("tier") ? object.get("tier").getAsString().replace('_', ' ') : null;
					String material = object.has("material") ? object.get("material").getAsString() : null;
					all.putIfAbsent(name, new Info(tier, material));
				}
			}
		} catch (RuntimeException e) {
			return Map.of();
		}
		return all;
	}

	public void set(List<String> names) {
		this.names = List.copyOf(names);
		java.util.Set<String> lower = new java.util.HashSet<>();
		names.forEach(n -> lower.add(n.toLowerCase(Locale.ROOT)));
		this.lowerNames = lower;
	}

	/** The SkyBlock item id to name table, for items held in an inventory ({@code HYPERION} is "Hyperion"). */
	public void setIds(Map<String, String> byId) {
		this.byId = Map.copyOf(byId);
	}

	/** The item's name for its SkyBlock id, or null. */
	public String nameForId(String id) {
		return id == null ? null : byId.get(id);
	}

	/**
	 * The item's own name inside a display name: color codes, a pet's "[Lvl 100]", stars and other symbols at the end, and any
	 * reforge words at the start are taken off ("Heroic Hyperion ✪✪✪✪✪" is "Hyperion"). Reforges are found without a list:
	 * leading words are dropped until what is left is a known item name. Unknown names come back cleaned but whole.
	 */
	public String baseName(String displayName) {
		String clean = displayName.replaceAll("§.", "").replaceAll("^\\[Lvl \\d+]\\s*", "")
				.replaceAll("[^\\p{L}\\p{Nd})'\\]]+$", "").strip();          // stars and ➎ are not decimal digits
		String[] words = clean.split(" ");
		for (int start = 0; start < words.length; start++) {
			String rest = String.join(" ", java.util.Arrays.copyOfRange(words, start, words.length));
			if (lowerNames.contains(rest.toLowerCase(Locale.ROOT))) {
				return rest;
			}
		}
		return clean;
	}

	public int size() {
		return names.size();
	}

	/** The item names in Hypixel's answer, color codes removed, each once (first spelling kept), sorted. */
	public static List<String> parse(String json) {
		Map<String, String> unique = new LinkedHashMap<>();
		try {
			JsonObject answer = JsonParser.parseString(json).getAsJsonObject();
			for (JsonElement item : answer.getAsJsonArray("items")) {
				JsonElement name = item.getAsJsonObject().get("name");
				if (name != null && name.isJsonPrimitive()) {
					String clean = name.getAsString().replaceAll("§.", "").strip();
					if (!clean.isEmpty()) {
						unique.putIfAbsent(clean.toLowerCase(Locale.ROOT), clean);
					}
				}
			}
		} catch (RuntimeException e) {
			return List.of();
		}
		List<String> sorted = new ArrayList<>(unique.values());
		sorted.sort(String.CASE_INSENSITIVE_ORDER);
		return sorted;
	}

	/** The id to name table in Hypixel's answer. */
	public static Map<String, String> parseIds(String json) {
		Map<String, String> ids = new java.util.HashMap<>();
		try {
			for (JsonElement item : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("items")) {
				JsonObject object = item.getAsJsonObject();
				if (object.has("id") && object.has("name")) {
					ids.put(object.get("id").getAsString(), object.get("name").getAsString().replaceAll("§.", "").strip());
				}
			}
		} catch (RuntimeException e) {
			return Map.of();
		}
		return ids;
	}

	/** Up to {@code limit} names for what is typed, best first. */
	public List<String> match(String query, int limit) {
		String q = query.strip().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			return List.of();
		}
		record Hit(String name, int score) {
		}
		List<Hit> hits = new ArrayList<>();
		for (String name : names) {
			int score = score(name.toLowerCase(Locale.ROOT), q);
			if (score >= 0) {
				hits.add(new Hit(name, score));
			}
		}
		hits.sort(Comparator.comparingInt(Hit::score).thenComparingInt(h -> h.name().length())
				.thenComparing(Hit::name, String.CASE_INSENSITIVE_ORDER));
		List<String> best = new ArrayList<>();
		for (int i = 0; i < hits.size() && best.size() < limit; i++) {
			best.add(hits.get(i).name());
		}
		return best;
	}

	/** 0 same name, 1 starts with it, 2 a word starts with it, 3 contains it, 4 the words' first letters (aotd), -1 no match. */
	static int score(String name, String query) {
		if (name.equals(query)) {
			return 0;
		}
		if (name.startsWith(query)) {
			return 1;
		}
		if (name.contains(" " + query) || name.contains("-" + query) || name.contains("'" + query)) {
			return 2;
		}
		if (query.length() >= 3 && name.contains(query)) {
			return 3;
		}
		if (query.length() >= 2 && initials(name).startsWith(query)) {
			return 4;
		}
		return -1;
	}

	private static String initials(String name) {
		StringBuilder first = new StringBuilder();
		for (String word : name.split("[ -]+")) {
			if (!word.isEmpty()) {
				first.append(word.charAt(0));
			}
		}
		return first.toString();
	}
}
