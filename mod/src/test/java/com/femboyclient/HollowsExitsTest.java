package com.femboyclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Learning the ways out of the Nucleus from walks, and keeping them in a file. */
class HollowsExitsTest {

	/** Walks from the spawn in the Nucleus straight east (+x), a quarter block a tick, this many blocks. */
	private static void walkEast(HollowsExits exits, double blocks) {
		for (double d = 0; d <= blocks; d += 0.25) {
			exits.observe(513.5 + d, 106, 526.5);
		}
	}

	@Test
	void aWalkOutOfTheNucleusIsLearnedForItsDirection() {
		HollowsExits exits = new HollowsExits(null);
		assertNull(exits.toward(4));
		walkEast(exits, 90);
		assertEquals(1, exits.count());
		List<Long> east = exits.toward(4);
		assertNotNull(east);
		assertEquals(HollowsExits.SPAWN_X, HollowsExits.px(east.get(0)), HollowsExits.SPAWN_RADIUS, "starts at the spawn");
		assertTrue(HollowsExits.px(east.get(east.size() - 1)) > 513 + HollowsExits.OUTSIDE - 5, "ends outside");
		assertEquals(east, exits.toward(3), "the nearest way out serves a direction with none of its own");
	}

	@Test
	void aWarpHalfWayIsNotAWayOut() {
		HollowsExits exits = new HollowsExits(null);
		walkEast(exits, 40);
		exits.observe(700, 106, 526.5);                   // put somewhere else by the server
		for (double d = 0; d < 20; d += 0.25) {
			exits.observe(700 + d, 106, 526.5);
		}
		assertEquals(0, exits.count());
	}

	@Test
	void waysOutAreKeptInAFile(@TempDir Path dir) {
		Path file = dir.resolve("femboyclient").resolve("hollows-exits.txt");
		HollowsExits first = new HollowsExits(file);
		walkEast(first, 90);
		HollowsExits second = new HollowsExits(file);
		assertEquals(1, second.count());
		assertEquals(first.toward(4).size(), second.toward(4).size());
	}
}
