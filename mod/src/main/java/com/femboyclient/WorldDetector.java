package com.femboyclient;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out which SkyBlock world you are in from what the game already shows:
 * the location line on the sidebar, and the "Area:" line in the tab list.
 *
 * It only looks for a location. Other sidebar lines are ignored, and in particular
 * the line with the date and the server code (for example "09/20/26 M5B") is never
 * read, stored or logged.
 *
 * Kept free of Minecraft classes so it can be tested on its own.
 */
public final class WorldDetector {

	/**
	 * @param world the friendly world name, or null if no area was found
	 * @param area  the area text it came from, for the log. Null when world is null.
	 * @param known true if the area is in the table (SkyBlockWorlds). False when the area was
	 *              not in the table and its own name is used as the world instead.
	 */
	public record Detection(String world, String area, boolean known) {
	}

	/**
	 * The shape of a name we accept as a world. Letters, spaces, apostrophes and hyphens only,
	 * and never a digit, so a server or lobby code (such as "mini264C") can never get through.
	 * The website applies the same rule.
	 */
	private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile(" +");
	private static final java.util.regex.Pattern BRACKETS = java.util.regex.Pattern.compile("\\s*\\(.*\\)\\s*$");
	private static final java.util.regex.Pattern AREA_CHARS = java.util.regex.Pattern.compile("[A-Za-z' -]+");
	private static final java.util.regex.Pattern PLAIN_NAME = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z' -]{1,31}");

	private WorldDetector() {
	}

	/**
	 * @param sidebarLines the sidebar lines, only when the sidebar is SkyBlock's
	 * @param tabTexts     the text shown for each tab list entry
	 */
	public static Detection detect(List<String> sidebarLines, List<String> tabTexts) {
		for (String line : sidebarLines) {
			String area = areaText(line);
			SkyBlockWorlds.World world = SkyBlockWorlds.byArea(area);
			if (world != null) {
				return new Detection(world.name(), area, true);
			}
		}

		// The tab list's "Area:" line. Remember the first one in case it is not in the table.
		String tabArea = null;
		for (String text : tabTexts) {
			String clean = ScoreboardReader.strip(text);
			int colon = clean.indexOf(':');
			if (colon > 0 && clean.substring(0, colon).trim().equalsIgnoreCase("Area")) {
				String area = areaText(clean.substring(colon + 1));
				SkyBlockWorlds.World world = SkyBlockWorlds.byArea(area);
				if (world != null) {
					return new Detection(world.name(), area, true);
				}
				if (tabArea == null) {
					tabArea = area;
				}
			}
		}

		// An area the table does not know (for example "Deep Caverns"): the tab list still says
		// where you are, so use that name rather than "Unknown". Only a plain name is used.
		if (tabArea != null) {
			String name = String.join(" ", SPACES.split(tabArea.trim()));
			if (PLAIN_NAME.matcher(name).matches()) {
				return new Detection(name, name, false);
			}
		}

		return new Detection(null, null, false);
	}

	/**
	 * The area name in a sidebar line. The game puts a location symbol in front of it
	 * and dungeons add a floor in brackets, for example "The Catacombs (F7)".
	 */
	public static String areaText(String line) {
		String text = BRACKETS.matcher(ScoreboardReader.strip(line)).replaceAll("");

		// Drop the symbol, and any other decoration, before the first real letter or digit.
		// Some symbols, such as the Rift's, are genuine letters, so only plain ASCII counts.
		int start = 0;
		while (start < text.length() && !isAscii(text.charAt(start))) {
			start++;
		}
		return text.substring(start).trim();
	}

	private static boolean isAscii(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
	}

	/**
	 * Sidebar lines that could have been an area name but were not recognised, for the
	 * game log. Lines with digits or other punctuation, such as the date and server
	 * line, the purse and the time, are left out, so no code that identifies a server
	 * can end up here.
	 */
	public static List<String> unrecognised(List<String> sidebarLines) {
		List<String> out = new ArrayList<>();
		for (String line : sidebarLines) {
			String area = areaText(line);
			if (area.length() >= 4 && area.length() <= 40 && AREA_CHARS.matcher(area).matches()
					&& SkyBlockWorlds.byArea(area) == null) {
				out.add(area);
			}
		}
		return out;
	}
}
