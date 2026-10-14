package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetSwapTest {

	private static PetSwap.Item pet(String name, String... lore) {
		return new PetSwap.Item(name, List.of(lore));
	}

	@Test
	void heatIsReadOffTheSidebar() {
		assertEquals(12, PetSwap.heat(List.of("Crystal Hollows", "Heat: ♨ 12", "Purse: 1,234")));
		assertEquals(0, PetSwap.heat(List.of("Heat: ♨ 0")));
		assertEquals(PetSwap.NO_NUMBER, PetSwap.heat(List.of("Heat: IMMUNE")));
		assertNull(PetSwap.heat(List.of("Purse: 1,234", "Bits: 50")));
		assertNull(PetSwap.heat(List.of()));
	}

	@Test
	void theMagmaFieldsAreKnownByTheSidebarAreaLine() {
		assertTrue(PetSwap.inMagmaFields(List.of("10/13/26 m109DN", "⏣ Magma Fields", "Heat: ♨ 3")));
		assertTrue(PetSwap.inMagmaFields(List.of(" ⏣ Magma Fields")));
		assertFalse(PetSwap.inMagmaFields(List.of("⏣ Crystal Nucleus", "Heat: ♨ 0")));
		assertFalse(PetSwap.inMagmaFields(List.of("[123] Someone: Magma Fields")));
	}

	@Test
	void enteringTheMagmaFieldsAsksForBalOnce() {
		PetSwap swap = new PetSwap();
		assertNull(swap.onReading(0, true, false));
		assertEquals(PetSwap.Want.BAL, swap.onReading(0, true, true));
		assertNull(swap.onReading(0, true, true));                  // still there: nothing new
		assertNull(swap.onReading(1, true, true));                  // heat going up asks for nothing
		assertNull(swap.onReading(1, true, false));                 // leaving asks for nothing
		assertEquals(PetSwap.Want.BAL, swap.onReading(1, true, true));   // entering again: Bal again
	}

	@Test
	void heatGoingDownAsksForTheOtherPetAndGoingUpForNothing() {
		PetSwap swap = new PetSwap();
		assertNull(swap.onReading(5, true, false));                 // first reading: nothing to compare with
		assertNull(swap.onReading(6, true, false));
		assertNull(swap.onReading(6, true, false));
		assertEquals(PetSwap.Want.BACK, swap.onReading(5, true, false));
		assertNull(swap.onReading(7, true, false));
	}

	@Test
	void enteringTheMagmaFieldsWinsOverHeatGoingDownInTheSameReading() {
		PetSwap swap = new PetSwap();
		swap.onReading(5, true, false);
		assertEquals(PetSwap.Want.BAL, swap.onReading(4, true, true));
	}

	@Test
	void aHeatLineWithoutANumberKeepsTheLastOneAndLeavingTheHollowsForgetsIt() {
		PetSwap swap = new PetSwap();
		swap.onReading(10, true, false);
		assertNull(swap.onReading(PetSwap.NO_NUMBER, true, false));
		assertEquals(PetSwap.Want.BACK, swap.onReading(9, true, false));
		assertNull(swap.onReading(null, false, false));             // left the Crystal Hollows
		assertNull(swap.onReading(3, true, false));
		assertEquals(PetSwap.Want.BACK, swap.onReading(2, true, false));
	}

	@Test
	void theHeatLineGoingAwayInTheHollowsCountsAsHeatGoingDownOnce() {
		PetSwap swap = new PetSwap();
		swap.onReading(1, true, true);
		for (int tick = 1; tick < PetSwap.GONE_TICKS; tick++) {
			assertNull(swap.onReading(null, true, true));           // a short gap is not enough
		}
		assertEquals(PetSwap.Want.BACK, swap.onReading(null, true, true));
		for (int tick = 0; tick < 100; tick++) {
			assertNull(swap.onReading(null, true, true));           // only once
		}
	}

	@Test
	void aBrieflyMissingHeatLineChangesNothing() {
		PetSwap swap = new PetSwap();
		swap.onReading(4, true, false);
		for (int tick = 0; tick < 5; tick++) {
			assertNull(swap.onReading(null, true, false));
		}
		assertNull(swap.onReading(4, true, false));
	}

	@Test
	void petNamesLoseLevelColorsAndSkinMark() {
		assertEquals("Bal", PetSwap.petName("§7[Lvl 100] §6Bal"));
		assertEquals("Scatha", PetSwap.petName("[Lvl 87] Scatha ✦"));
		assertEquals("Golden Dragon", PetSwap.petName("[Lvl 200] Golden Dragon"));
		assertEquals("Mithril Golem", PetSwap.petName("Mithril Golem"));
	}

	@Test
	void chatLinesSayWhichPetIsOut() {
		assertEquals("Bal", PetSwap.summoned("You summoned your Bal!"));
		assertEquals("Scatha", PetSwap.summoned("You summoned your Scatha ✦!"));
		assertEquals("Scatha", PetSwap.summoned("Autopet equipped your [Lvl 100] Scatha! VIEW RULE"));
		assertNull(PetSwap.summoned("[123] Someone: You summoned your Bal!"));
		assertTrue(PetSwap.despawned("You despawned your Bal!"));
		assertFalse(PetSwap.despawned("You summoned your Bal!"));
	}

	@Test
	void thePetsMenuIsKnownByItsTitle() {
		assertTrue(PetSwap.isPetsMenu("Pets"));
		assertTrue(PetSwap.isPetsMenu("Pets (2/3)"));
		assertFalse(PetSwap.isPetsMenu("Pets: Bal"));
		assertFalse(PetSwap.isPetsMenu("SkyBlock Menu"));
	}

	@Test
	void theMenuFindsThePetTheActiveOneAndTheNextPage() {
		List<PetSwap.Item> items = List.of(
				pet("Black Stained Glass Pane"),
				pet("[Lvl 100] Scatha", "Mining Fortune +100", "Click to despawn!"),
				pet("[Lvl 100] Bal ✦", "Heat immunity", "Click to summon!"),
				pet("Next Page", "Page 2"));
		assertEquals(2, PetSwap.find(items, "Bal"));
		assertEquals(1, PetSwap.find(items, "scatha"));
		assertEquals(-1, PetSwap.find(items, "Golden Dragon"));
		assertEquals("Scatha", PetSwap.active(items));
		assertTrue(PetSwap.isActive(items.get(1)));
		assertFalse(PetSwap.isActive(items.get(2)));
		assertEquals(3, PetSwap.nextPage(items));
		assertEquals(-1, PetSwap.nextPage(items.subList(0, 3)));
		assertNull(PetSwap.active(items.subList(2, 4)));
	}

	@Test
	void onlyBalIsBal() {
		assertTrue(PetSwap.isBal("Bal"));
		assertTrue(PetSwap.isBal("bal"));
		assertFalse(PetSwap.isBal("Scatha"));
		assertFalse(PetSwap.isBal(null));
	}
}
