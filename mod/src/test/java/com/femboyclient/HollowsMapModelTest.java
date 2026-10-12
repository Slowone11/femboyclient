package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HollowsMapModelTest {

	private static HollowsMapModel.Structure grotto(int id, int x, int z, int jasper, boolean best, boolean mansion) {
		return new HollowsMapModel.Structure("Fairy Grotto", id, x, 150, z, 10, 5, 12, jasper, best, null, true, mansion);
	}

	private static final HollowsMapModel.Structure TEMPLE =
			new HollowsMapModel.Structure("Jungle Temple", 1, 315, 105, 320, 30, 10, 40, 0, false, null, false, false);

	private static final List<HollowsMapModel.Structure> FOUND = List.of(
			grotto(1, 256, 607, 90, false, false), grotto(2, 480, 246, 142, true, false), TEMPLE);

	@Test
	void grottosAreNamedByTheirJasperRank() {
		assertEquals("Fairy Grotto 1", HollowsMapModel.label(FOUND.get(1), FOUND), "142 jasper is first");
		assertEquals("Fairy Grotto 2", HollowsMapModel.label(FOUND.get(0), FOUND));
		assertEquals("Jungle Temple", HollowsMapModel.label(TEMPLE, FOUND));
	}

	@Test
	void aMansionIsCalledSoAndWinsTheBestLine() {
		List<HollowsMapModel.Structure> all = List.of(grotto(1, 256, 607, 90, false, true), grotto(2, 480, 246, 142, true, false));

		assertEquals("Mansion grotto", HollowsMapModel.label(all.get(0), all));
		assertTrue(all.get(0).starred());
		assertEquals(all.get(0), HollowsMapModel.bestGrotto(all), "Mansion beats the one with more jasper");
	}

	@Test
	void onlyMansionsAreLeftWhenTheMapIsShown() {
		List<HollowsMapModel.Structure> all = List.of(grotto(1, 256, 607, 90, false, true), grotto(2, 480, 246, 142, true, false), TEMPLE);
		HollowsMapModel.Model m = HollowsMapModel.mansionsOnly(all);

		assertEquals(1, m.structures().size());
		assertTrue(m.structures().get(0).isMansion());
		assertTrue(HollowsMapModel.mansionsOnly(FOUND).isEmpty(), "no Mansion, nothing drawn");
	}

	@Test
	void withoutAMansionTheGrottoWithTheMostJasperIsTheBestOne() {
		HollowsMapModel.Structure best = HollowsMapModel.bestGrotto(FOUND);
		assertNotNull(best);
		assertEquals(142, best.jasper());
		assertNull(HollowsMapModel.bestGrotto(List.of(TEMPLE)));
	}

	@Test
	void theListPutsGrottosFirstMostJasperFirst() {
		List<HollowsMapModel.Structure> order = HollowsMapModel.listOrder(FOUND);
		assertEquals(142, order.get(0).jasper());
		assertEquals(90, order.get(1).jasper());
		assertEquals("Jungle Temple", order.get(2).name());
	}

	@Test
	void theMapIsFlatWithNorthUp() {
		assertEquals(0.0, HollowsMapModel.project(190, 700), 1e-9);
		assertEquals(700.0, HollowsMapModel.project(835, 700), 1e-9);
		assertEquals(350.0, HollowsMapModel.project(512, 700), 1);
		assertTrue(HollowsMapModel.project(300, 700) < HollowsMapModel.project(700, 700), "a lower z (north) is higher on the map");
	}

	@Test
	void everyStructureHasItsColour() {
		assertEquals(0xF2A6D4, HollowsMapModel.colour("Fairy Grotto"));
		assertEquals(0x46C46A, HollowsMapModel.colour("Jungle Temple"));
		assertEquals(0x8B93A5, HollowsMapModel.colour("Something Else"));
	}

	@Test
	void thePlacementDemoHasAMansion() {
		assertFalse(HollowsMapModel.demo().isEmpty());
		assertTrue(HollowsMapModel.bestGrotto(HollowsMapModel.demo().structures()).isMansion());
	}

	@Test
	void theOverlayHasSizesThatFitTheirParts() {
		HollowsMapModel.Model model = new HollowsMapModel.Model(FOUND);
		assertEquals(158, HollowsHud.panelWidth(150));
		int withList = HollowsHud.panelHeight(model, 150);
		int small = HollowsHud.panelHeight(model, 100);
		assertTrue(withList > 150 + HollowsHud.HEADER, "the map, its heading and the list");
		assertEquals(0, HollowsHud.rows(model, 100), "a small map has no list");
		assertTrue(small < withList);
	}

	@Test
	void resetPutsTheMapBackInTheTopLeftCornerAtTheNormalSizeWithoutTouchingWhetherItShows() {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(), new Properties());
		assertTrue(config.hud);
		assertEquals(4, config.hudX);
		assertEquals(150, config.hudSize);

		config.hud = false;
		config.hudX = 90;
		config.hudY = 70;
		config.hudSize = 220;

		HollowsMap.reset(config);
		assertEquals(4, config.hudX);
		assertEquals(4, config.hudY);
		assertEquals(150, config.hudSize);
		assertFalse(config.hud, "reset moves it, it does not turn it back on");
	}

	@Test
	void savedPlacementIsReadBackAndKeptInsideItsLimits() {
		Properties user = new Properties();
		user.setProperty("hudX", "-20");
		user.setProperty("hudSize", "9999");
		user.setProperty("hud", "false");
		user.setProperty("hudYaw", "200");                  // from the old 3D map: ignored now
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(), user);

		assertEquals(0, config.hudX);
		assertEquals(HollowsHud.MAX_SIZE, config.hudSize);
		assertFalse(config.hud);
	}
}
