package com.femboyclient;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Minecraft-free half of {@link PetSwapper}: reads the Crystal Hollows heat off the sidebar, decides which pet the heat
 * asks for, reads pet names out of chat lines, and finds pets in the {@code /pets} menu. Kept free of game classes so plain
 * JUnit tests cover it.
 */
final class PetSwap {

	/** The pet put on when heat goes up. */
	static final String BAL = "Bal";

	/** Which pet the heat asks for: Bal when it went up, the pet you had before Bal when it went down. */
	enum Want {
		BAL, BACK
	}

	/** A sidebar heat reading that has no number (for example "Heat: IMMUNE"). */
	static final int NO_NUMBER = -1;

	/** "Heat: ♨ 12" (color codes already gone): the number is the first run of digits after the colon. */
	private static final Pattern HEAT = Pattern.compile("^Heat:\\D*(\\d+)");

	private static final Pattern SUMMONED = Pattern.compile("^You summoned your (.+?)!$");
	private static final Pattern DESPAWNED = Pattern.compile("^You despawned your (.+?)!$");
	private static final Pattern AUTOPET = Pattern.compile("^Autopet equipped your (.+?)!(?: VIEW RULE)?$");

	/** "Pets" or "Pets (2/3)": the title of the /pets menu, color codes already gone. */
	private static final Pattern PETS_MENU = Pattern.compile("^Pets(?: \\(\\d+/\\d+\\))?$");

	/** A slot of the /pets menu: its name and lore lines, color codes already gone. */
	record Item(String name, List<String> lore) {
	}

	/** Heat at the last reading that had a number, or {@link #NO_NUMBER} when not known. */
	private int lastHeat = NO_NUMBER;

	/**
	 * The heat on the sidebar lines (color codes already gone): its number, {@link #NO_NUMBER} when the heat line has no
	 * number, or null when there is no heat line (not in the Crystal Hollows).
	 */
	static Integer heat(List<String> lines) {
		for (String line : lines) {
			if (line.startsWith("Heat:")) {
				Matcher m = HEAT.matcher(line);
				if (!m.find()) {
					return NO_NUMBER;
				}
				try {
					return Integer.parseInt(m.group(1));
				} catch (NumberFormatException e) {
					return NO_NUMBER;
				}
			}
		}
		return null;
	}

	/**
	 * Takes a new heat reading (see {@link #heat}) and says which pet it asks for: {@link Want#BAL} when heat went up since the
	 * last reading, {@link Want#BACK} when it went down, null when it did not change or there is nothing to compare with. No heat
	 * line forgets the last number; a heat line without a number keeps it.
	 */
	Want onHeat(Integer heat) {
		if (heat == null) {
			lastHeat = NO_NUMBER;
			return null;
		}
		if (heat == NO_NUMBER) {
			return null;
		}
		int before = lastHeat;
		lastHeat = heat;
		if (before == NO_NUMBER || heat == before) {
			return null;
		}
		return heat > before ? Want.BAL : Want.BACK;
	}

	/** Forgets the last heat reading. */
	void reset() {
		lastHeat = NO_NUMBER;
	}

	/** Whether this pet name is Bal. */
	static boolean isBal(String pet) {
		return pet != null && pet.equalsIgnoreCase(BAL);
	}

	/** "[Lvl 100] Bal ✦" -> "Bal": the pet's own name, without level, color codes or the skin mark and other symbols at the end. */
	static String petName(String display) {
		return display.replaceAll("§.", "").strip().replaceAll("^\\[Lvl [^\\]]*]\\s*", "")
				.replaceAll("[^\\p{L}\\p{Nd})]+$", "").strip();
	}

	/** The pet a chat line says was just put on ("You summoned your Bal!", an Autopet line), or null. */
	static String summoned(String line) {
		for (Pattern pattern : new Pattern[] {SUMMONED, AUTOPET}) {
			Matcher m = pattern.matcher(line);
			if (m.matches()) {
				String name = petName(m.group(1));
				return name.isEmpty() ? null : name;
			}
		}
		return null;
	}

	/** Whether a chat line says your pet was put away ("You despawned your Bal!"). */
	static boolean despawned(String line) {
		return DESPAWNED.matcher(line).matches();
	}

	/** Whether a menu title (color codes already gone) is the /pets menu. */
	static boolean isPetsMenu(String title) {
		return PETS_MENU.matcher(title).matches();
	}

	/** Whether a menu slot is a pet: pets are named "[Lvl 100] Bal". */
	static boolean isPet(Item item) {
		return item.name().startsWith("[Lvl ");
	}

	/** Whether a menu slot is the pet out now: its lore offers to despawn it. */
	static boolean isActive(Item item) {
		if (!isPet(item)) {
			return false;
		}
		for (String line : item.lore()) {
			if (line.toLowerCase(Locale.ROOT).contains("to despawn")) {
				return true;
			}
		}
		return false;
	}

	/** The name of the pet out now, as the menu shows it, or null when none on this page is. */
	static String active(List<Item> items) {
		for (Item item : items) {
			if (isActive(item)) {
				return petName(item.name());
			}
		}
		return null;
	}

	/** The slot of the first pet with this name on this page, or -1. */
	static int find(List<Item> items, String pet) {
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			if (isPet(item) && petName(item.name()).equalsIgnoreCase(pet)) {
				return i;
			}
		}
		return -1;
	}

	/** The slot of the "Next Page" arrow, or -1 on the last page. */
	static int nextPage(List<Item> items) {
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).name().equals("Next Page")) {
				return i;
			}
		}
		return -1;
	}
}
