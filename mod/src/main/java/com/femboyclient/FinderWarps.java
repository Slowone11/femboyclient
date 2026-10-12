package com.femboyclient;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The worlds the Player Finder can hop to, and the /warp name for each.
 *
 * TO ADD OR CORRECT A WORLD: edit the put(...) lines below, and the same line in FINDER_WARPS in
 * server\app.py. A test checks that the two lists are equal.
 *
 * Every name here is in SkyHanni's list of SkyBlock warp commands (constants/Warps.json in
 * github.com/hannibal002/SkyHanni-REPO), which is kept up to date by the SkyHanni mod's authors. It is
 * not tested in this game by me. Some warps must be unlocked first (with a Travel Scroll or by visiting),
 * and the game refuses a locked one; the finder then stops with "warp_failed" after two tries.
 *
 * Left out on purpose: Private Island (that is /is), Dungeons, The Rift (a separate dimension), Crystal
 * Hollows (/warp crystals does not put you into a Hollows lobby) and Jerry's Workshop (winter only).
 * The finder refuses those instead of typing a command that does nothing.
 *
 * The finder only ever types "/is" and "/warp <name from this table>". It never types text that
 * came from the website.
 */
public final class FinderWarps {

	/** Where "/is" takes you. */
	public static final String ISLAND_WORLD = "Private Island";

	private static final Map<String, String> WARPS = new LinkedHashMap<>();

	static {
		WARPS.put("Hub", "hub");
		WARPS.put("Dungeon Hub", "dhub");
		WARPS.put("Spider's Den", "spider");
		WARPS.put("The End", "end");
		WARPS.put("The Park", "park");
		WARPS.put("The Farming Islands", "barn");
		WARPS.put("Crimson Isle", "nether");
		WARPS.put("Deep Caverns", "deep");
		WARPS.put("Gold Mine", "gold");
		WARPS.put("Dwarven Mines", "mines");
		WARPS.put("Garden", "garden");
		WARPS.put("Galatea", "galatea");
	}

	private FinderWarps() {
	}

	/** The command to type (without the slash), for example "warp hub", or null if the world has no warp. */
	public static String commandFor(String world) {
		if (world == null) {
			return null;
		}
		for (Map.Entry<String, String> entry : WARPS.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(world.strip())) {
				return "warp " + entry.getValue();
			}
		}
		return null;
	}

	/** The worlds that can be searched, in a stable order. */
	public static java.util.List<String> worlds() {
		return java.util.List.copyOf(WARPS.keySet());
	}

	/** True if the two world names are the same world, ignoring case. */
	public static boolean sameWorld(String a, String b) {
		return a != null && b != null && a.strip().toLowerCase(Locale.ROOT).equals(b.strip().toLowerCase(Locale.ROOT));
	}
}
