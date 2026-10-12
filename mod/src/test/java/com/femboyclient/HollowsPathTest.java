package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paths through small made-up worlds: a floor at y 0 with walls, steps, drops and lava on it. */
class HollowsPathTest {

	/** Air inside a 40 x 40 box from y -10 to 20, a stone floor at y 0, and whatever a test puts there. */
	private static final class World implements HollowsPath.Blocks {
		final Map<Long, HollowsPath.Cell> cells = new HashMap<>();

		World() {
			for (int x = 0; x < 40; x++) {
				for (int z = 0; z < 40; z++) {
					set(x, 0, z, HollowsPath.Cell.SOLID);
				}
			}
		}

		void set(int x, int y, int z, HollowsPath.Cell cell) {
			cells.put(HollowsPath.pack(x, y, z), cell);
		}

		/** A wall across the whole floor at this x, this many blocks tall. */
		void wall(int x, int height) {
			for (int z = 0; z < 40; z++) {
				for (int y = 1; y <= height; y++) {
					set(x, y, z, HollowsPath.Cell.SOLID);
				}
			}
		}

		@Override
		public HollowsPath.Cell at(int x, int y, int z) {
			if (x < 0 || x >= 40 || z < 0 || z >= 40 || y < -10 || y > 20) {
				return HollowsPath.Cell.UNLOADED;
			}
			return cells.getOrDefault(HollowsPath.pack(x, y, z), HollowsPath.Cell.OPEN);
		}
	}

	/** Anywhere with x of at least this much. */
	private static HollowsPath.Goal eastOf(int minX) {
		return (x, y, z) -> Math.max(0, minX - x);
	}

	private static HollowsPath run(World world, int x, int y, int z, HollowsPath.Goal goal) {
		HollowsPath search = new HollowsPath(world, x, y, z, goal);
		while (!search.step(100)) {
			// a little at a time, the way the walker runs it
		}
		return search;
	}

	@Test
	void packsPositionsBothWays() {
		for (int[] p : new int[][] {{0, 0, 0}, {513, 106, 513}, {-5, -3, 12}, {823, 255, -900}}) {
			long packed = HollowsPath.pack(p[0], p[1], p[2]);
			assertEquals(p[0], HollowsPath.x(packed));
			assertEquals(p[1], HollowsPath.y(packed));
			assertEquals(p[2], HollowsPath.z(packed));
		}
	}

	@Test
	void walksStraightAcrossAFlatFloor() {
		HollowsPath search = run(new World(), 2, 1, 5, eastOf(12));
		assertTrue(search.reachesGoal());
		List<Long> path = search.path();
		assertEquals(10, path.size());
		long last = path.get(path.size() - 1);
		assertEquals(12, HollowsPath.x(last));
		assertEquals(1, HollowsPath.y(last));
	}

	@Test
	void jumpsUpOneBlockButNotTwo() {
		World low = new World();
		low.wall(6, 1);                                   // a step: the far side is one block higher
		for (int x = 7; x < 40; x++) {
			for (int z = 0; z < 40; z++) {
				low.set(x, 1, z, HollowsPath.Cell.SOLID);
			}
		}
		HollowsPath up = run(low, 2, 1, 5, eastOf(12));
		assertTrue(up.reachesGoal());
		assertEquals(2, HollowsPath.y(up.path().get(up.path().size() - 1)));

		World high = new World();
		high.wall(6, 2);
		HollowsPath blocked = run(high, 2, 1, 5, eastOf(12));
		assertFalse(blocked.reachesGoal());
		assertEquals(5, HollowsPath.x(blocked.path().get(blocked.path().size() - 1)), "it gets as close as it can");
	}

	@Test
	void dropsDownALedgeButNotOffACliff() {
		World ledge = new World();
		for (int x = 0; x < 6; x++) {
			for (int z = 0; z < 40; z++) {
				for (int y = 1; y <= 3; y++) {
					ledge.set(x, y, z, HollowsPath.Cell.SOLID);   // standing on a platform 3 blocks above the floor
				}
			}
		}
		assertTrue(run(ledge, 2, 4, 5, eastOf(12)).reachesGoal());

		World cliff = new World();
		for (int x = 0; x < 40; x++) {
			for (int z = 0; z < 40; z++) {
				cliff.set(x, 0, z, HollowsPath.Cell.OPEN);
				cliff.set(x, x < 6 ? 0 : -8, z, HollowsPath.Cell.SOLID);
			}
		}
		assertFalse(run(cliff, 2, 1, 5, eastOf(12)).reachesGoal());
	}

	@Test
	void goesAroundLava() {
		World world = new World();
		for (int z = 0; z < 30; z++) {
			world.set(6, 0, z, HollowsPath.Cell.DANGER);  // a stream of lava with a way round at the far end
			world.set(6, 1, z, HollowsPath.Cell.DANGER);
		}
		HollowsPath search = run(world, 2, 1, 5, eastOf(12));
		assertTrue(search.reachesGoal());
		for (long pos : search.path()) {
			assertFalse(HollowsPath.x(pos) == 6 && HollowsPath.z(pos) < 30, "never steps on the lava");
		}
	}

	@Test
	void sealedInMeansNoPath() {
		World world = new World();
		world.wall(4, 4);
		HollowsPath search = run(world, 3, 1, 5, eastOf(12));
		assertFalse(search.reachesGoal());
		assertTrue(search.path().isEmpty());
	}

	private static HollowsPath dig(World world, int x, int y, int z, HollowsPath.Goal goal) {
		HollowsPath search = new HollowsPath(world, x, y, z, goal, true);
		while (!search.step(100)) {
			// a little at a time
		}
		return search;
	}

	@Test
	void digsThroughStoneButGoesRoundWhatCannotBeBroken() {
		World world = new World();
		for (int z = 0; z < 40; z++) {
			for (int y = 1; y <= 4; y++) {
				world.set(6, y, z, z < 30 ? HollowsPath.Cell.SOLID : HollowsPath.Cell.STONE);  // a structure, stone at the far end
			}
		}
		assertFalse(run(world, 2, 1, 5, eastOf(12)).reachesGoal(), "no walking way");
		HollowsPath search = dig(world, 2, 1, 5, eastOf(12));
		assertTrue(search.reachesGoal());
		for (long pos : search.path()) {
			assertFalse(HollowsPath.x(pos) == 6 && HollowsPath.z(pos) < 30, "never through the structure");
		}
	}

	@Test
	void walksRoundRatherThanDigWhenTheWayRoundIsShort() {
		World world = new World();
		for (int z = 0; z < 8; z++) {
			for (int y = 1; y <= 3; y++) {
				world.set(6, y, z, HollowsPath.Cell.STONE);
			}
		}
		HollowsPath search = dig(world, 2, 1, 5, eastOf(12));
		assertTrue(search.reachesGoal());
		for (long pos : search.path()) {
			assertFalse(HollowsPath.x(pos) == 6 && HollowsPath.z(pos) < 8, "round the end of the wall, not through it");
		}
	}

	@Test
	void walksUpSlabsWithoutJumpingButNeverUpASlabAndAHalf() {
		World world = new World();
		for (int z = 0; z < 40; z++) {
			world.set(6, 1, z, HollowsPath.Cell.LOW);     // a slab step,
			for (int x = 7; x < 40; x++) {
				world.set(x, 1, z, HollowsPath.Cell.SOLID);   // then a full block higher
			}
		}
		HollowsPath search = run(world, 2, 1, 5, eastOf(12));
		assertTrue(search.reachesGoal());
		long onSlab = search.path().stream().filter(p -> HollowsPath.x(p) == 6).findFirst().orElseThrow();
		assertEquals(2, HollowsPath.y(onSlab), "standing on the slab");
		assertEquals(1.5, HollowsPath.standHeight(world, 6, 2, 5), "half a block up from the floor");

		World tooHigh = new World();
		for (int z = 0; z < 40; z++) {
			tooHigh.set(6, 1, z, HollowsPath.Cell.LOW);   // a slab on the floor,
			tooHigh.set(7, 1, z, HollowsPath.Cell.SOLID); // a block next to it,
			tooHigh.set(7, 2, z, HollowsPath.Cell.SOLID); // and another on that: 1.5 up from the slab
		}
		HollowsPath blocked = run(tooHigh, 2, 1, 5, eastOf(12));
		assertFalse(blocked.reachesGoal());
	}

	@Test
	void feetOnASlabAreInTheBlockAboveIt() {
		assertEquals(101, HollowsPath.feetBlock(100.5));
		assertEquals(100, HollowsPath.feetBlock(100.0));
		assertEquals(101, HollowsPath.feetBlock(100.0625), "on a carpet");
	}

	@Test
	void startsFromTheBlockAboveASlab() {
		World world = new World();
		world.set(2, 1, 5, HollowsPath.Cell.SOLID);       // the feet are "in" the slab
		assertTrue(run(world, 2, 1, 5, eastOf(12)).reachesGoal());
	}
}
