package com.femboyclient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the MansionFinder map shows, and the maths to place it: a flat map of the Crystal Hollows seen from straight above, north up.
 * Everything on it was found by this game (see {@link HollowsSync}); nothing comes from the website.
 *
 * Free of Minecraft classes so it can be tested on its own.
 */
final class HollowsMapModel {

	/** One structure on the map. {@code sizeX} and {@code sizeZ} are the box around it, in blocks. */
	record Structure(String name, int n, int x, int y, int z, int sizeX, int sizeY, int sizeZ, int jasper, boolean best,
			String variant, boolean scanned, boolean mansion) {

		boolean isGrotto() {
			return name.equals("Fairy Grotto");
		}

		/** A Mansion: a Fairy Grotto made of what a Mansion is made of (see {@link HollowsSync.MansionInfo}). */
		boolean isMansion() {
			return mansion;
		}

		/** The gold ring: the most jasper of the lobby, or a Mansion. */
		boolean starred() {
			return best || isMansion();
		}
	}

	/** Everything for one lobby. */
	record Model(List<Structure> structures) {

		static final Model EMPTY = new Model(List.of());

		boolean isEmpty() {
			return structures.isEmpty();
		}
	}

	/** The edges of the map: the Hollows are about 202..823 in x and z, with a small margin. */
	static final int LOW = 190, HIGH = 835;

	/** The structure colors, as 0xRRGGBB. */
	static int colour(String name) {
		return switch (name) {
			case "Jungle Temple" -> 0x46C46A;
			case "Mines of Divan" -> 0x5AA9E6;
			case "Goblin Queen's Den" -> 0xE0A458;
			case "Lost Precursor City" -> 0xB48EAD;
			case "Khazad-dûm" -> 0xE05A5A;
			case "Fairy Grotto" -> 0xF2A6D4;
			case "Dragon's Lair" -> 0xF0B429;
			case "Odawa" -> 0x8FD6C6;
			default -> 0x8B93A5;
		};
	}

	/**
	 * Where a world coordinate lands on a map that is {@code size} pixels across. x goes right and z goes down, so north (lower z) is
	 * always at the top.
	 */
	static double project(int world, int size) {
		return (world - LOW) / (double) (HIGH - LOW) * size;
	}

	/** A grotto's place in its lobby by jasper, most first, starting at 1. Other structures have none. */
	static int grottoRank(Structure s, List<Structure> all) {
		List<Structure> grottos = all.stream().filter(Structure::isGrotto)
				.sorted(Comparator.comparingInt(Structure::jasper).reversed().thenComparingInt(Structure::n)).toList();
		return grottos.indexOf(s) + 1;
	}

	/** What a structure is called: "Mines of Divan", "Mansion grotto", "Fairy Grotto 2". */
	static String label(Structure s, List<Structure> all) {
		if (!s.isGrotto()) {
			return s.name();
		}
		return s.isMansion() ? "Mansion grotto" : "Fairy Grotto " + grottoRank(s, all);
	}

	/** The line under the map: the mansion grotto if there is one, else the grotto with the most jasper. Null when none. */
	static Structure bestGrotto(List<Structure> all) {
		List<Structure> grottos = all.stream().filter(s -> s.isGrotto() && s.jasper() > 0).toList();
		return grottos.stream().filter(Structure::isMansion).findFirst()
				.orElseGet(() -> grottos.stream().filter(Structure::best).findFirst().orElse(null));
	}

	/** The rows of the list: grottos first (most jasper first), then the rest by name. */
	static List<Structure> listOrder(List<Structure> all) {
		List<Structure> out = new ArrayList<>(all);
		out.sort(Comparator.comparingInt((Structure s) -> s.isGrotto() ? -1 - s.jasper() : 0).thenComparing(Structure::name));
		return out;
	}

	/** Only the Mansion grottos. Every other structure is left out of what is drawn. */
	static Model mansionsOnly(List<Structure> found) {
		return new Model(found.stream().filter(s -> s.isGrotto() && s.isMansion()).toList());
	}

	/** A made-up lobby to show while you place the map, before anything real has been found. */
	static Model demo() {
		return new Model(List.of(
				new Structure("Fairy Grotto", 1, 480, 118, 246, 30, 94, 30, 142, true, null, true, true)));
	}

	private HollowsMapModel() {
	}
}
