package com.femboyclient;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Minecraft-free half of {@link PetSwapper}: reads the Crystal Hollows heat and area off the sidebar, decides which pet
 * they ask for, reads pet names out of chat lines, and finds pets in the {@code /pets} menu. Kept free of game classes so
 * plain JUnit tests cover it.
 */
final class PetSwap {

	/** The pet put on when you enter the Magma Fields. */
	static final String BAL = "Bal";

	/** Which pet is asked for: Bal when you enter the Magma Fields, the pet you had before Bal when heat goes down. */
	enum Want {
		BAL, BACK
	}

	/** A sidebar heat reading that has no number (for example "Heat: IMMUNE"). */
	static final int NO_NUMBER = -1;

	/**
	 * Ticks (20 a second) the heat line must stay gone, while still in the Crystal Hollows, before it counts as heat 0. The
	 * line can vanish when heat runs out; the wait keeps a line briefly missing while the sidebar redraws from counting.
	 */
	static final int GONE_TICKS = 30;

	/** "Heat: ♨ 12" (color codes already gone): the number is the first run of digits after the colon. */
	private static final Pattern HEAT = Pattern.compile("^Heat:\\D*(\\d+)");

	private static final Pattern SUMMONED = Pattern.compile("^You summoned your (.+?)!$");
	private static final Pattern DESPAWNED = Pattern.compile("^You despawned your (.+?)!$");
	private static final Pattern AUTOPET = Pattern.compile("^Autopet equipped your (.+?)!(?: VIEW RULE)?$");

	/** The sidebar's area line in the Magma Fields, "⏣ Magma Fields" (color codes already gone): any symbols, then the name. */
	private static final Pattern MAGMA_FIELDS = Pattern.compile("^[^\\p{L}]*Magma Fields$");

	/** "Pets" or "Pets (2/3)": the title of the /pets menu, color codes already gone. */
	private static final Pattern PETS_MENU = Pattern.compile("^Pets(?: \\(\\d+/\\d+\\))?$");

	/** A slot of the /pets menu: its name and lore lines, color codes already gone. */
	record Item(String name, List<String> lore) {
	}

	/** Heat at the last reading that had a number, or {@link #NO_NUMBER} when not known. */
	private int lastHeat = NO_NUMBER;
	/** Readings in a row without a heat line while in the Crystal Hollows. */
	private int goneTicks;
	/** Whether the last reading was in the Magma Fields. */
	private boolean wasInMagmaFields;

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

	/** Whether the sidebar lines (color codes already gone) say you are in the Magma Fields. */
	static boolean inMagmaFields(List<String> lines) {
		for (String line : lines) {
			if (MAGMA_FIELDS.matcher(line).matches()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Takes a new reading, one per tick, and says which pet it asks for: {@link Want#BAL} when you just entered the Magma Fields,
	 * {@link Want#BACK} when heat went down since the last reading, else null. Going up in heat asks for nothing.
	 *
	 * @param heat           see {@link #heat}
	 * @param inHollows      whether you are in the Crystal Hollows at all
	 * @param inMagmaFields  see {@link #inMagmaFields}
	 */
	Want onReading(Integer heat, boolean inHollows, boolean inMagmaFields) {
		boolean entered = inMagmaFields && !wasInMagmaFields;
		wasInMagmaFields = inMagmaFields;
		boolean cooled = heatWentDown(heat, inHollows);
		return entered ? Want.BAL : cooled ? Want.BACK : null;
	}

	/**
	 * Whether this heat reading is lower than the last one with a number. A heat line without a number keeps the last number. No
	 * heat line in the Crystal Hollows for {@link #GONE_TICKS} counts as heat 0 (once); no heat line anywhere else forgets the
	 * last number.
	 */
	private boolean heatWentDown(Integer heat, boolean inHollows) {
		if (heat == null) {
			if (!inHollows) {
				lastHeat = NO_NUMBER;
				goneTicks = 0;
				return false;
			}
			if (++goneTicks != GONE_TICKS) {
				return false;
			}
			heat = 0;                      // the line went away: no heat left
		} else {
			goneTicks = 0;
		}
		if (heat == NO_NUMBER) {
			return false;
		}
		int before = lastHeat;
		lastHeat = heat;
		return before != NO_NUMBER && heat < before;
	}

	/** Forgets the last reading. */
	void reset() {
		lastHeat = NO_NUMBER;
		goneTicks = 0;
		wasInMagmaFields = false;
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
