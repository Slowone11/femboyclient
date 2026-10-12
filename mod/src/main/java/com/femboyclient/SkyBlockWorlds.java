package com.femboyclient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The SkyBlock worlds, the names you can type after /scanner, and the area names
 * the game shows on the sidebar and tab list for each.
 *
 * TO ADD OR CORRECT A WORLD: edit the ALL list below. Nothing else needs to change.
 *
 * VERIFICATION STATUS: only "Village" (Hub) and "Your Island" (Private Island) have
 * been checked against real game data, from your own logs. Every other area name is
 * from memory and NOT verified. When the mod cannot place an area, it writes the
 * unrecognised sidebar text to the game log so the table can be corrected.
 *
 * A world is an island, not a spot on it: walking around the Hub changes the area
 * (Village, Farm, Coal Mine...) but stays in the Hub. So several area names map to
 * one world. "Crystal Nucleus" is such an area inside the Crystal Hollows.
 */
public final class SkyBlockWorlds {

	/**
	 * @param name    the friendly name that is stored and displayed
	 * @param aliases what /scanner accepts as well as the name itself
	 * @param areas   the area names the game shows while you are in this world
	 */
	public record World(String name, List<String> aliases, List<String> areas) {
	}

	/** What is saved when the world cannot be worked out. */
	public static final String UNKNOWN = "Unknown";

	public static final List<World> ALL = List.of(
			new World("Hub", List.of("hub"),
					List.of("Hub", "Village", "Farm", "Coal Mine", "Forest", "Ruins", "Graveyard", "Mountain",
							"Fishing Outpost", "Wilderness", "High Level", "Bazaar Alley", "Colosseum",
							"Wizard Tower", "Fisherman's Hut", "Community Center", "Auction House", "Bank",
							"Library", "Flower House")),

			new World("Dungeon Hub", List.of("dungeon hub", "dhub"),
					List.of("Dungeon Hub")),

			// The sidebar shows "The Catacombs (F7)"; the bracket part is ignored.
			new World("Dungeons", List.of("dungeon", "dungeons", "catacombs"),
					List.of("The Catacombs")),

			new World("Crystal Hollows", List.of("crystal hollows", "crystal nucleus", "nucleus", "hollows"),
					List.of("Crystal Hollows", "Crystal Nucleus", "Jungle", "Mithril Deposits", "Goblin Holdout",
							"Goblin Queen's Den", "Precursor Remnants", "Lost Precursor City", "Magma Fields",
							"Khazad-dûm", "Fairy Grotto", "Dragon's Lair", "Jungle Temple", "Mines of Divan",
							"Odawa")),

			new World("Dwarven Mines", List.of("dwarven mines", "mines"),
					List.of("Dwarven Mines", "Dwarven Village", "Gates to the Mines", "Royal Mines", "Royal Palace",
							"Palace Bridge", "Rampart's Quarry", "Forge Basin", "Cliffside Veins", "Lava Springs",
							"Goblin Burrows", "Far Reserve", "Upper Mines", "The Mist", "Great Ice Wall",
							"The Lift", "Aristocrat Passage", "Hanging Court", "Divan's Gateway",
							"Glacite Tunnels", "Great Glacite Lake")),

			new World("The End", List.of("the end", "end"),
					List.of("The End", "Dragon's Nest", "Void Sepulture", "Zealot Bruiser Hideout")),

			new World("Crimson Isle", List.of("crimson isle", "crimson", "nether"),
					List.of("Crimson Isle", "Blazing Volcano", "Dragontail", "Scarleton", "Mystic Marsh",
							"Smoldering Tomb", "Stronghold", "Odger's Hut", "Burning Desert", "Cathedral",
							"Magma Chamber", "Forgotten Skull", "Dojo")),

			new World("Spider's Den", List.of("spiders den", "spider"),
					List.of("Spider's Den", "Spider Mound", "Arachne's Burrow", "Arachne's Sanctuary",
							"Gunpowder Mines", "Top of Nest", "Grandma's House")),

			new World("The Park", List.of("the park", "park"),
					List.of("The Park", "Birch Park", "Spruce Woods", "Savanna Woodland", "Dark Thicket",
							"Jungle Island", "Howling Cave", "Melody's Plateau", "Viking Longhouse")),

			new World("The Farming Islands", List.of("the farming islands", "farming islands", "farming", "barn"),
					List.of("The Farming Islands", "The Barn", "Mushroom Desert", "Desert Settlement", "Oasis",
							"Glowing Mushroom Cave", "Jake's House", "Trapper's Den", "Desert Mountain",
							"Mushroom Gorge", "Overgrown Mushroom Cave", "Shepherd's Keep", "Windmill", "Farmhouse")),

			new World("Garden", List.of("garden"),
					List.of("Garden")),

			new World("Private Island", List.of("island", "private island"),
					List.of("Your Island", "Private Island")),

			new World("The Rift", List.of("the rift", "rift"),
					List.of("The Rift", "Wyld Woods", "Village Plaza", "Mirrorverse", "Dreadfarm", "Black Lagoon",
							"Living Cave", "Stillgore Château"))
	);

	private static final Map<String, World> BY_COMMAND_NAME = new HashMap<>();
	private static final Map<String, World> BY_AREA = new HashMap<>();

	static {
		for (World world : ALL) {
			BY_COMMAND_NAME.put(normalise(world.name()), world);
			for (String alias : world.aliases()) {
				BY_COMMAND_NAME.put(normalise(alias), world);
			}
			for (String area : world.areas()) {
				World clash = BY_AREA.put(normalise(area), world);
				if (clash != null && clash != world) {
					// A table mistake would silently send players to the wrong world, so fail loudly.
					throw new IllegalStateException("area '" + area + "' is listed under both "
							+ clash.name() + " and " + world.name());
				}
			}
		}
	}

	private SkyBlockWorlds() {
	}

	/**
	 * Lower case, apostrophes dropped, anything else that is not a letter or digit
	 * turned into one space. So "Spider's Den", "spiders den" and "SPIDERS  DEN" all match.
	 */
	public static String normalise(String text) {
		StringBuilder out = new StringBuilder();
		boolean space = true;
		for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
			if (c == '\'' || c == '’') {
				continue;
			}
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				out.append(c);
				space = false;
			} else if (!space) {
				out.append(' ');
				space = true;
			}
		}
		return out.toString().trim();
	}

	/** The world a name typed after /scanner refers to, ignoring case, or empty if it is not one. */
	public static Optional<World> byCommandName(String input) {
		return Optional.ofNullable(BY_COMMAND_NAME.get(normalise(input)));
	}

	/** The world an area name belongs to, or null if the area is not in the table. */
	public static World byArea(String area) {
		return BY_AREA.get(normalise(area));
	}

	/** The friendly names of every world, in table order. */
	public static List<String> names() {
		List<String> names = new ArrayList<>();
		for (World world : ALL) {
			names.add(world.name());
		}
		return names;
	}

	/** Everything that can be typed after "/scanner true ": names and aliases, lower case. */
	public static List<String> commandWords() {
		List<String> words = new ArrayList<>();
		for (World world : ALL) {
			words.add(world.name().toLowerCase(Locale.ROOT));
			for (String alias : world.aliases()) {
				if (!words.contains(alias)) {
					words.add(alias);
				}
			}
		}
		return words;
	}
}
