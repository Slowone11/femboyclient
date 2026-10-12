package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives MansionFinder with a fake world made of chunks, each just a set of block kinds. */
class MansionFinderTest {

	/** A fake game: chunk key -> the kinds of block in it. */
	private static final class FakeChunks implements MansionFinder.Chunks {
		final Map<Long, Map<String, Integer>> world = new HashMap<>();
		final Map<Long, Integer> identity = new HashMap<>();
		final Map<Long, List<int[]>> bands = new HashMap<>();
		int located;
		int presentCalls;

		void put(int cx, int cz, String... kinds) {
			Map<String, Integer> counts = new HashMap<>();
			for (String kind : kinds) {
				counts.put(kind, 10);
			}
			put(cx, cz, counts);
		}

		void put(int cx, int cz, Map<String, Integer> counts) {
			world.put(MansionFinder.key(cx, cz), counts);
			identity.merge(MansionFinder.key(cx, cz), 1, Integer::sum);
		}

		@Override
		public List<Long> loaded(int chunkX, int chunkZ, int radius) {
			List<Long> keys = new ArrayList<>(world.keySet());
			keys.sort((a, b) -> Integer.compare(dist(a, chunkX, chunkZ), dist(b, chunkX, chunkZ)));
			return keys;
		}

		private static int dist(long key, int cx, int cz) {
			return Math.max(Math.abs((int) (key >> 32) - cx), Math.abs((int) key - cz));
		}

		int probes;

		@Override
		public boolean hasJasper(int cx, int cz) {
			probes++;
			Map<String, Integer> counts = world.get(MansionFinder.key(cx, cz));
			return counts != null && MansionFinder.JASPER.stream().anyMatch(counts::containsKey);
		}

		@Override
		public Integer identity(int cx, int cz) {
			return identity.get(MansionFinder.key(cx, cz));
		}

		@Override
		public Map<String, Integer> blocks(int cx, int cz) {
			presentCalls++;
			return world.get(MansionFinder.key(cx, cz));
		}

		@Override
		public List<int[]> locateBands(int cx, int cz, Set<String> kinds) {
			List<int[]> given = bands.get(MansionFinder.key(cx, cz));
			return given != null ? given : MansionFinder.Chunks.super.locateBands(cx, cz, kinds);
		}

		@Override
		public int[] locate(int cx, int cz, Set<String> kinds) {
			located++;
			return new int[] {cx * 16, 60, cz * 16, cx * 16 + 15, 80, cz * 16 + 15};
		}
	}

	record Hit(String structure, int id, int[] box, int jasper, Set<String> kinds) {
	}

	private final FakeChunks chunks = new FakeChunks();
	private final MansionFinder scanner = new MansionFinder();
	private final List<Hit> hits = new ArrayList<>();

	/** Runs enough ticks for every chunk to be looked at. */
	private void run(int ticks, int playerX, int playerZ) {
		for (int i = 0; i < ticks; i++) {
			scanner.tick(chunks, playerX, playerZ, (structure, id, box, jasper, kinds) -> hits.add(new Hit(structure, id, box, jasper, kinds)));
		}
	}

	@Test
	void chunksOutsideTheHollowsAreIgnored() {
		chunks.put(2, 2, counts("magenta_stained_glass_pane", 30));
		chunks.put(60, 60, counts("magenta_stained_glass_pane", 30));
		run(30, 330, 330);
		assertTrue(hits.isEmpty());
	}

	@Test
	void aChunkThatWasUnloadedAndLoadedAgainIsLookedAtAgain() {
		chunks.put(20, 20, "stone");
		run(30, 330, 330);
		chunks.put(20, 20, counts("magenta_stained_glass_pane", 30));     // same place, new chunk object
		run(30, 330, 330);
		assertEquals(1, hits.size());
	}

	// ------------------------------------------------------------------ fairy grottos, found by their jasper

	private static Map<String, Integer> counts(Object... pairs) {
		Map<String, Integer> out = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			out.put((String) pairs[i], (Integer) pairs[i + 1]);
		}
		return out;
	}

	@Test
	void aChunkWithJasperIsAFairyGrottoWithNothingLearnedFirst() {
		chunks.put(20, 20, counts("stone", 900, "magenta_stained_glass_pane", 30, "magenta_stained_glass", 12));
		run(30, 330, 330);

		assertEquals(1, hits.size());
		assertEquals("Fairy Grotto", hits.get(0).structure());
		assertEquals(42, hits.get(0).jasper(), "panes and blocks both count");
		assertArrayEquals(new int[] {320, 60, 320, 335, 80, 335}, hits.get(0).box());
	}

	@Test
	void chunksWithoutJasperAreNotGrottos() {
		chunks.put(20, 20, counts("stone", 900, "yellow_stained_glass", 50, "red_stained_glass_pane", 20));
		run(30, 330, 330);
		assertTrue(hits.isEmpty(), "topaz and ruby are everywhere in the Hollows");
	}

	@Test
	void theTwoChunksOfOneGrottoAreOneGrottoWithTheirJasperAdded() {
		chunks.put(20, 20, counts("magenta_stained_glass_pane", 30));
		chunks.put(21, 20, counts("magenta_stained_glass_pane", 10));
		run(30, 330, 330);

		MansionFinderTest.Hit last = hits.get(hits.size() - 1);
		assertEquals(40, last.jasper());
		assertArrayEquals(new int[] {320, 60, 320, 351, 80, 335}, last.box());
		assertTrue(hits.size() <= 2, "chunks that load together are one report");
	}

	@Test
	void twoGrottosFarApartAreTwoGrottos() {
		chunks.put(15, 15, counts("magenta_stained_glass_pane", 30));
		chunks.put(40, 40, counts("magenta_stained_glass_pane", 90));
		run(60, 330, 330);

		assertEquals(2, hits.stream().map(h -> h.box()[0]).distinct().count());
	}

	@Test
	void aGrottoIsReportedAgainOnlyWhenSomethingAboutItChanged() {
		chunks.put(20, 20, counts("magenta_stained_glass_pane", 30));
		run(30, 330, 330);
		run(200, 330, 330);
		assertEquals(1, hits.size());
	}

	// ------------------------------------------------------------------ speed

	@Test
	void aChunkTheGameJustLoadedIsProbedOnTheNextTickWithoutWaitingForASweep() {
		run(1, 330, 330);                                  // the first sweep is over, nothing loaded yet
		chunks.put(20, 20, counts("magenta_stained_glass_pane", 30));
		scanner.chunkLoaded(20, 20);
		hits.clear();
		run(1, 330, 330);

		assertEquals(1, hits.size(), "found on the very next tick");
	}

	@Test
	void aBurstOfChunksIsAllProbedInOneTickAndTheGrottoAmongThemIsFound() {
		for (int x = 13; x < 45; x++) {
			for (int z = 13; z < 20; z++) {
				chunks.put(x, z, counts("stone", 900));            // 224 plain chunks
			}
		}
		chunks.put(44, 19, counts("magenta_stained_glass_pane", 30, "stone", 100));
		run(1, 330, 330);

		assertEquals(1, hits.size());
		assertTrue(chunks.probes > 200, "probed " + chunks.probes);
		assertEquals(0, chunks.located - 1, "only the chunk with jasper was ever searched block by block");
	}

	@Test
	void theProbeNeverCountsBlocksOfAChunkWithoutJasper() {
		chunks.put(20, 20, counts("stone", 900));
		run(1, 330, 330);
		assertTrue(chunks.probes >= 1);
		assertEquals(0, chunks.presentCalls, "nothing counts the blocks of a chunk without jasper");
	}

	@Test
	void everyReportOfOneGrottoCarriesTheSameId() {
		chunks.put(20, 20, counts("magenta_stained_glass_pane", 30));
		run(5, 330, 330);
		chunks.put(21, 20, counts("magenta_stained_glass_pane", 10));
		scanner.chunkLoaded(21, 20);
		run(5, 330, 330);

		assertEquals(2, hits.size());
		assertEquals(hits.get(0).id(), hits.get(1).id());
	}

	@Test
	void everyChunkThatWasLookedAtStaysScannedEvenAfterItUnloadsUntilYouLeaveTheLobby() {
		chunks.put(20, 20, "stone");
		chunks.put(21, 20, "stone");
		run(30, 330, 330);
		assertTrue(scanner.isCovered(20, 20) && scanner.isCovered(21, 20));
		assertFalse(scanner.isCovered(22, 20));
		assertEquals(2, scanner.coveredCount());

		chunks.world.remove(MansionFinder.key(20, 20));        // out of view now, still scanned
		chunks.identity.remove(MansionFinder.key(20, 20));
		run(60, 330, 330);
		assertTrue(scanner.isCovered(20, 20));

		scanner.reset();
		assertFalse(scanner.isCovered(20, 20));
		assertEquals(0, scanner.coveredCount());
	}

	@Test
	void twoGrottosStackedInOneColumnOfChunksAreTwoGrottosNotOneTallOne() {
		chunks.bands.put(MansionFinder.key(20, 20), List.of(new int[] {320, 60, 320, 335, 78, 335, 30}, new int[] {320, 140, 320, 335, 160, 335, 50}));
		chunks.bands.put(MansionFinder.key(21, 20), List.of(new int[] {336, 62, 320, 351, 80, 335, 20}, new int[] {336, 141, 320, 351, 158, 335, 40}));
		chunks.put(20, 20, "magenta_stained_glass", "stone");
		chunks.put(21, 20, "magenta_stained_glass", "stone");
		run(30, 330, 330);

		java.util.Map<Integer, Hit> last = new java.util.HashMap<>();
		hits.forEach(h -> last.put(h.id(), h));
		assertEquals(2, last.size(), "one grotto low down and one high up");
		for (Hit h : last.values()) {
			assertTrue(h.box()[4] - h.box()[1] < 30, "neither is 80 blocks tall: " + java.util.Arrays.toString(h.box()));
			assertEquals(h.box()[1] < 100 ? 50 : 90, h.jasper());
		}
	}

	@Test
	void pieceOfGrottoCloseInHeightAreOneGrottoEvenWithAGapInsideIt() {
		chunks.bands.put(MansionFinder.key(20, 20), List.of(new int[] {320, 60, 320, 335, 70, 335, 30}));
		chunks.bands.put(MansionFinder.key(21, 20), List.of(new int[] {336, 74, 320, 351, 90, 335, 20}));
		chunks.put(20, 20, "magenta_stained_glass");
		chunks.put(21, 20, "magenta_stained_glass");
		run(30, 330, 330);

		assertEquals(1, hits.stream().map(Hit::id).distinct().count());
		assertEquals(50, hits.get(hits.size() - 1).jasper());
	}

	@Test
	void theDistinctiveKindsOfTheMansionAreReportedEvenWhenTheyAreNotRare() {
		chunks.put(20, 20, "magenta_stained_glass", "ladder", "wall_torch", "stone");
		scanner.setDistinctive(Set.of("ladder", "wall_torch", "chest"));
		run(30, 330, 330);

		assertEquals(1, hits.size());
		assertEquals(Set.of("ladder", "wall_torch"), hits.get(0).kinds(), "only the kinds that tell the Mansion apart are reported, stone is not");
	}

	@Test
	void theMansionIsFoundByItsBlocksEvenWhenItsJasperIsBrokenIntoVeins() {
		scanner.setMansionCore(Set.of("lilac", "allium", "azure_bluet", "cracked_stone_bricks", "mossy_stone_bricks"), 4);
		chunks.put(20, 20, "stone", "lilac", "allium", "cracked_stone_bricks");            // no jasper in these two
		chunks.put(21, 20, "stone", "oak_leaves");
		chunks.put(22, 22, "magenta_stained_glass");                                        // but a little jasper close by
		run(30, 330, 330);
		assertTrue(hits.stream().noneMatch(h -> h.jasper() == 0), "three of the five core kinds is not enough");

		chunks.put(21, 21, "stone", "azure_bluet", "mossy_stone_bricks");
		run(30, 330, 330);

		Hit found = hits.stream().filter(h -> h.jasper() == 0).findFirst().orElseThrow();
		assertEquals("Fairy Grotto", found.structure());
		assertEquals(Set.of("lilac", "allium", "azure_bluet", "cracked_stone_bricks", "mossy_stone_bricks"), found.kinds());
	}

	@Test
	void coreKindsSpreadOverAWiderAreaAreNotAMansionEvenWithJasperNearby() {
		// What a real false flag looked like: flowers in a few chunks, a temple's stone bricks a few chunks along, a grotto close by.
		scanner.setMansionCore(Set.of("lilac", "allium", "azure_bluet", "cracked_stone_bricks", "mossy_stone_bricks"), 4);
		chunks.put(24, 13, "lilac");
		chunks.put(24, 14, "lilac", "magenta_stained_glass_pane");
		chunks.put(25, 13, "allium");
		chunks.put(25, 14, "lilac");
		chunks.put(26, 14, "cracked_stone_bricks");
		chunks.put(27, 15, "cracked_stone_bricks", "mossy_stone_bricks");
		chunks.put(28, 16, "azure_bluet");
		run(40, 400, 220);

		assertTrue(hits.stream().noneMatch(h -> h.jasper() == 0), "no 2 x 2 chunks hold four of the five: " + hits);
	}

	@Test
	void fourOfTheCoreKindsInOneGroupOfFourChunksIsAMansion() {
		scanner.setMansionCore(Set.of("lilac", "allium", "azure_bluet", "cracked_stone_bricks", "mossy_stone_bricks"), 4);
		chunks.put(24, 14, "lilac", "magenta_stained_glass_pane");
		chunks.put(25, 14, "allium", "cracked_stone_bricks");
		chunks.put(24, 15, "azure_bluet");
		run(40, 400, 220);

		Hit found = hits.stream().filter(h -> h.jasper() == 0).findFirst().orElseThrow();
		assertEquals(Set.of("lilac", "allium", "azure_bluet", "cracked_stone_bricks"), found.kinds());
	}

	@Test
	void theSameBlocksInPlainTerrainWithNoJasperAnywhereAreNotAMansion() {
		scanner.setMansionCore(Set.of("lilac", "allium", "azure_bluet", "cracked_stone_bricks", "mossy_stone_bricks"), 4);
		chunks.put(20, 20, "stone", "lilac", "allium", "cracked_stone_bricks");            // jungle: flowers, and a temple's stone bricks
		chunks.put(21, 20, "stone", "azure_bluet", "mossy_stone_bricks");
		run(30, 330, 330);

		assertTrue(hits.isEmpty(), "no jasper close by, so it is not a Fairy Grotto at all");
	}

	@Test
	void chunksFarApartDoNotAddUpToAMansion() {
		scanner.setMansionCore(Set.of("lilac", "allium", "azure_bluet", "cracked_stone_bricks"), 3);
		chunks.put(14, 14, "lilac", "allium");
		chunks.put(45, 45, "azure_bluet", "cracked_stone_bricks");
		run(30, 330, 330);

		assertTrue(hits.isEmpty());
	}

	@Test
	void anotherBuildWithSomeOfTheFlowersAndStoneBricksAroundIsNotAMansion() {
		// A second real false flag: a grotto with two of the three flowers, and bricks that grow in the terrain around it.
		Set<String> core = Set.of("lilac", "allium", "azure_bluet", "cracked_stone_bricks", "mossy_stone_bricks");
		scanner.setMansionCore(core, 5);
		chunks.put(39, 29, "allium", "azure_bluet", "cracked_stone_bricks", "mossy_stone_bricks", "magenta_stained_glass_pane");
		chunks.put(40, 29, "cracked_stone_bricks", "mossy_stone_bricks", "magenta_stained_glass_pane");
		chunks.put(41, 29, "allium", "azure_bluet", "cracked_stone_bricks", "mossy_stone_bricks");
		run(40, 640, 470);

		assertTrue(hits.stream().noneMatch(h -> h.jasper() == 0), "no lilac anywhere: four of the five is not the Mansion");
	}

	@Test
	void aBlockOnlyOtherBuildsHaveRulesAGroupOutEvenWithAllTheCoreKinds() {
		Set<String> core = Set.of("lilac", "allium", "azure_bluet", "cracked_stone_bricks", "mossy_stone_bricks");
		scanner.setMansionCore(core, 5, Set.of("mossy_cobblestone"));
		chunks.put(24, 14, "lilac", "allium", "azure_bluet", "magenta_stained_glass_pane");
		chunks.put(25, 14, "cracked_stone_bricks", "mossy_stone_bricks", "mossy_cobblestone");
		run(40, 400, 220);

		assertTrue(hits.stream().noneMatch(h -> h.jasper() == 0), "mossy cobblestone is in the plain grottos");
	}

	@Test
	void allFiveCoreKindsTogetherAreAMansion() {
		Set<String> core = Set.of("lilac", "allium", "azure_bluet", "cracked_stone_bricks", "mossy_stone_bricks");
		scanner.setMansionCore(core, 5, Set.of("mossy_cobblestone"));
		chunks.put(24, 14, "lilac", "allium", "azure_bluet", "magenta_stained_glass_pane");
		chunks.put(25, 14, "cracked_stone_bricks", "mossy_stone_bricks");
		run(40, 400, 220);

		Hit found = hits.stream().filter(h -> h.jasper() == 0).findFirst().orElseThrow();
		assertEquals(core, found.kinds());
	}

	@Test
	void withoutAConfirmedMansionNothingIsLookedFor() {
		chunks.put(20, 20, "lilac", "allium", "azure_bluet", "cracked_stone_bricks");
		run(30, 330, 330);

		assertTrue(hits.isEmpty());
	}

	@Test
	void chunksOutsideTheHollowsAreNeverScanned() {
		assertFalse(scanner.isCovered(2, 2));
		assertFalse(scanner.isCovered(60, 60));
	}

	@Test
	void itSaysHowManyChunksAreLoadedAndHowFarTheyReach() {
		for (int x = 12; x <= 28; x++) {
			chunks.put(x, 20, counts("stone", 900));                // a row of 17 chunks: from 8 west of the player to 8 east
		}
		run(1, 20 * 16 + 5, 20 * 16 + 5);

		assertEquals(17, scanner.loadedChunks());
		assertEquals(8, scanner.reach());
	}
}
