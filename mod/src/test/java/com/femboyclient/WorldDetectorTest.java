package com.femboyclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real sidebars are from the game logs of this project. Areas other than "Village"
 * and "Your Island" are NOT verified against the real game; those tests check the
 * table is used correctly, not that Hypixel spells the area that way.
 */
class WorldDetectorTest {

	/** A real Hub sidebar, exactly as the game reported it. Note the server code in the date line. */
	private static final List<String> HUB = List.of(
			"Piggy: 4,954,188", "", "", "Early Autumn 12th", "Voidgloom Seraph I", " Village",
			"09/20/26 M5B", "7:30am ☀", "www.hypixel.net", "(1,512/2.8k) Combat", "", "Bits: 740", "", "Slayer Quest");

	/** A real Private Island sidebar (after the junk colour codes are stripped). */
	private static final List<String> ISLAND = List.of(
			"Piggy: 5,637,575", "10:30pm ☽", "", "09/20/26 m106V", "Bits: 740", "www.hypixel.net",
			"Early Autumn 9th", " Your Island", "", "", "Flight Duration: 21:49:48");

	private static WorldDetector.Detection detect(List<String> sidebar) {
		return WorldDetector.detect(sidebar, List.of());
	}

	@Test
	void theRealHubSidebarIsTheHub() {
		WorldDetector.Detection found = detect(HUB);
		assertEquals("Hub", found.world());
		assertEquals("Village", found.area());
	}

	@Test
	void theRealPrivateIslandSidebarIsThePrivateIsland() {
		assertEquals("Private Island", detect(ISLAND).world());
	}

	@Test
	void aLocationSymbolInFrontOfTheAreaIsIgnored() {
		assertEquals("Hub", detect(List.of("⏣ Village")).world());
		assertEquals("Hub", detect(List.of(" ⏣ Village")).world());
		assertEquals("Hub", detect(List.of("§7⏣ §bVillage")).world());
	}

	@Test
	void theRiftsSymbolIsARealLetterAndStillIgnored() {
		assertEquals("The Rift", detect(List.of("ф Wyld Woods")).world());
	}

	@Test
	void aDungeonFloorInBracketsIsIgnored() {
		assertEquals("Dungeons", detect(List.of("⏣ The Catacombs (F7)")).world());
		assertEquals("Dungeons", detect(List.of("The Catacombs (M7)")).world());
	}

	@Test
	void everyAreaInTheTableFindsItsOwnWorld() {
		for (SkyBlockWorlds.World world : SkyBlockWorlds.ALL) {
			for (String area : world.areas()) {
				assertEquals(world.name(), detect(List.of("⏣ " + area)).world(),
						"area '" + area + "' should be in " + world.name());
			}
		}
	}

	@Test
	void theAreaLineInTheTabListWorksWhenTheSidebarHasNone() {
		WorldDetector.Detection found = WorldDetector.detect(List.of("Piggy: 5"),
				List.of("Players (28)", "§bArea: §fCrystal Nucleus", "Gems: 100"));
		assertEquals("Crystal Hollows", found.world());
		assertEquals("Crystal Nucleus", found.area());
	}

	@Test
	void theSidebarIsPreferredOverTheTabList() {
		assertEquals("Hub", WorldDetector.detect(HUB, List.of("Area: Crystal Nucleus")).world());
	}

	@Test
	void anUnknownAreaGivesNoWorld() {
		WorldDetector.Detection found = detect(List.of("Piggy: 5", " Some Brand New Area", "www.hypixel.net"));
		assertNull(found.world());
		assertNull(found.area());
	}

	@Test
	void anEmptySidebarAndTabListGiveNoWorld() {
		assertNull(WorldDetector.detect(List.of(), List.of()).world());
	}

	@Test
	void theServerCodeLineIsNeverTreatedAsAnArea() {
		// These date lines carry the server code (M5B, m28CW, m106V). They must find nothing.
		for (String line : List.of("09/20/26 M5B", "09/20/26 m28CW", "09/20/26 m106V", "09/20/26 m144BT")) {
			assertNull(detect(List.of(line)).world());
		}
	}

	@ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
	@CsvSource(delimiter = '|', value = {
			"⏣ Village|Village",
			"  Village |Village",
			"The Catacombs (F7)|The Catacombs",
			"ф Wyld Woods|Wyld Woods",
			"Your Island|Your Island",
			"⏣ Spider's Den (x)|Spider's Den",
			"||"
	})
	void areaTextRemovesSymbolsAndBrackets(String line, String expected) {
		assertEquals(expected == null ? "" : expected, WorldDetector.areaText(line == null ? "" : line));
	}

	// -------------------------------------------- an area the table does not know

	/** The real sidebar from a Deep Caverns lobby: no location line the table knows. */
	private static final List<String> DEEP_CAVERNS = List.of(
			"Slayer Quest", "Voidgloom Seraph I", "Deep Caverns", "09/20/26 M5B", "www.hypixel.net");

	@Test
	void anAreaTheTableDoesNotKnowIsNamedFromTheTabListInsteadOfUnknown() {
		WorldDetector.Detection found = WorldDetector.detect(DEEP_CAVERNS,
				List.of("Info", "Area: Deep Caverns", "Players (21)"));

		assertEquals("Deep Caverns", found.world());
		assertEquals("Deep Caverns", found.area());
		assertFalse(found.known(), "it is not in the table");
	}

	@Test
	void theTabListAreaIsReadThroughFormattingCodesAndSpacing() {
		WorldDetector.Detection found = WorldDetector.detect(List.of(), List.of("§b§lArea: §r§7  Deep   Caverns ", "x"));
		assertEquals("Deep Caverns", found.world());
	}

	@Test
	void aLocationSymbolInTheTabAreaIsIgnored() {
		assertEquals("Deep Caverns", WorldDetector.detect(List.of(), List.of("Area: ⏣ Deep Caverns")).world());
	}

	@Test
	void aKnownAreaStillGivesItsWorldNotItsOwnName() {
		WorldDetector.Detection viaSidebar = WorldDetector.detect(HUB, List.of("Area: Deep Caverns"));
		assertEquals("Hub", viaSidebar.world());
		assertTrue(viaSidebar.known());

		WorldDetector.Detection viaTab = WorldDetector.detect(List.of(), List.of("Area: Village"));
		assertEquals("Hub", viaTab.world());
		assertTrue(viaTab.known());
	}

	@Test
	void aKnownTabAreaBeatsAnUnknownOneEarlierInTheList() {
		WorldDetector.Detection found = WorldDetector.detect(List.of(), List.of("Area: Mystery Place", "Area: Village"));
		assertEquals("Hub", found.world());
	}

	@Test
	void withoutAnAreaLineInTheTabListTheWorldIsStillUnknown() {
		assertNull(WorldDetector.detect(DEEP_CAVERNS, List.of("Info", "Players (21)", "Gems: 1,234")).world());
	}

	@Test
	void aTabAreaThatCouldBeAServerCodeOrJunkIsNeverUsedAsAWorld() {
		String[] notNames = {"Area: mini264C", "Area: m28CW", "Area: 09/20/26 M5B", "Area: Lobby 12", "Area:", "Area: ???",
				"Area: ⏣", "Area: A", "Area: " + "x".repeat(40), "Area: bad_name", "Area: Deep Caverns (x9)9"};
		for (String line : notNames) {
			WorldDetector.Detection found = WorldDetector.detect(List.of(), List.of(line));
			if (found.world() != null) {
				// Whatever it became, it holds no digit and no underscore, so no server code got through.
				for (char c : found.world().toCharArray()) {
					assertFalse(Character.isDigit(c) || c == '_', "must not become a world: " + line + " -> " + found.world());
				}
			}
		}
		assertNull(WorldDetector.detect(List.of(), List.of("Area: mini264C")).world());
		assertNull(WorldDetector.detect(List.of(), List.of("Area: Lobby 12")).world());
		assertNull(WorldDetector.detect(List.of(), List.of("Area: ???")).world());
	}

	@Test
	void anAreaLineMustStartWithAreaAndAColon() {
		assertNull(WorldDetector.detect(List.of(), List.of("Server: Deep Caverns", "Deep Caverns", "Area Deep Caverns")).world());
	}

	// ------------------------------------------------------- the log helper

	@Test
	void unrecognisedTextForTheLogNeverIncludesServerCodesDatesOrNumbers() {
		List<String> sidebar = List.of("Piggy: 5,228,367", "Early Autumn 12th", "Voidgloom Seraph I", " Some New Area",
				"09/20/26 M5B", "7:30am ☀", "www.hypixel.net", "(1,512/2.8k) Combat", "Bits: 740", "Slayer Quest");

		List<String> found = WorldDetector.unrecognised(sidebar);

		assertTrue(found.contains("Some New Area"));
		for (String text : found) {
			assertFalse(text.matches(".*\\d.*"), "no digits, so no server code: " + text);
			assertFalse(text.contains("M5B"));
		}
	}

	@Test
	void recognisedAreasAreNotReportedAsUnrecognised() {
		assertFalse(WorldDetector.unrecognised(HUB).contains("Village"));
	}
}
