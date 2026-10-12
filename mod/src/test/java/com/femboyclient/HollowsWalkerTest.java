package com.femboyclient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walker on a fake clock in a fake Hollows: a flat stone floor at y 99 over the whole area, a player who moves a quarter block
 * per tick while forward is held, and a scanner that marks every chunk within the view distance of the player as scanned.
 */
class HollowsWalkerTest {

	private static final class FakeGame implements HollowsWalker.Game, HollowsPath.Blocks, HollowsWalker.Coverage {
		double x = 300.5, y = 100, z = 300.5;
		float yaw, pitch;
		final HollowsTunnels tunnels = new HollowsTunnels();
		boolean forward, busy;
		long now = 1_000_000;
		int reach = 2;
		final Set<Long> covered = new HashSet<>();
		final List<String> commands = new ArrayList<>();
		final List<String> said = new ArrayList<>();
		int steers, releases;

		public double x() { return x; }
		public double y() { return y; }
		public double z() { return z; }
		public float yaw() { return yaw; }
		public float pitch() { return pitch; }
		public HollowsTunnels tunnels() { return tunnels; }
		final HollowsExits exits = new HollowsExits(null);
		public HollowsExits exits() { return exits; }
		public boolean onGround() { return true; }
		public boolean blocked() { return false; }
		public boolean climbing() { return false; }
		public boolean inWater() { return false; }
		public boolean busy() { return busy; }
		public HollowsPath.Blocks blocks() { return this; }

		public void steer(float yaw, float pitch, boolean forward, boolean jump, boolean sprint) {
			this.yaw = yaw;
			this.pitch = pitch;
			this.forward = forward;
			steers++;
		}

		public void release() {
			forward = false;
			releases++;
		}

		public void command(String command) { commands.add(command); }
		public void say(String text) { said.add(text); }
		public void log(String text) { }
		public void attack(boolean down) { attacking = down; }

		/** The Aspect of the Void: 8 blocks the way you look, stopping short of anything in the way. */
		public void use() {
			double yawRad = Math.toRadians(yaw), pitchRad = Math.toRadians(pitch);
			double dx = -Math.sin(yawRad) * Math.cos(pitchRad), dz = Math.cos(yawRad) * Math.cos(pitchRad);
			for (double d = 0.1; d <= 8; d += 0.1) {
				double nx = x + dx * d, nz = z + dz * d;
				if (!fits(nx, nz)) {
					break;
				}
				x = nx;
				z = nz;
			}
			hops++;
		}

		int hops;
		public void selectSlot(int slot) { }

		/** The block under the crosshair: a ray from the eyes along the view, as far as a player reaches. */
		public long lookingAt() {
			double yawRad = Math.toRadians(yaw), pitchRad = Math.toRadians(pitch);
			double dx = -Math.sin(yawRad) * Math.cos(pitchRad), dy = -Math.sin(pitchRad), dz = Math.cos(yawRad) * Math.cos(pitchRad);
			for (double d = 0; d < 4.5; d += 0.05) {
				int bx = (int) Math.floor(x + dx * d), by = (int) Math.floor(y + 1.62 + dy * d), bz = (int) Math.floor(z + dz * d);
				if (!HollowsPath.passable(this, bx, by, bz)) {
					return HollowsPath.pack(bx, by, bz);
				}
			}
			return Long.MIN_VALUE;
		}

		boolean attacking;
		long attackTarget = Long.MIN_VALUE;
		int attackTicks;
		final Set<Long> broken = new HashSet<>();
		public long millis() { return now; }

		/** The blocks: a flat floor at y 99 unless a test says otherwise. */
		HollowsPath.Blocks world = (x, y, z) -> y == 99 ? HollowsPath.Cell.SOLID
				: y < 99 ? HollowsPath.Cell.UNLOADED : HollowsPath.Cell.OPEN;

		public HollowsPath.Cell at(int x, int y, int z) {
			return broken.contains(HollowsPath.pack(x, y, z)) ? HollowsPath.Cell.OPEN : world.at(x, y, z);
		}

		/** Walls stop you, one direction at a time, like the game's own collisions. */
		private boolean fits(double px, double pz) {
			int feet = (int) Math.floor(y);
			for (double ox = -0.3; ox <= 0.3; ox += 0.6) {
				for (double oz = -0.3; oz <= 0.3; oz += 0.6) {
					for (int h = 0; h <= 1; h++) {
						if (!HollowsPath.passable(this, (int) Math.floor(px + ox), feet + h, (int) Math.floor(pz + oz))) {
							return false;
						}
					}
				}
			}
			return true;
		}

		public boolean covered(int chunkX, int chunkZ) { return covered.contains(MansionFinder.key(chunkX, chunkZ)); }
		public int reach() { return reach; }

		/** Everything scanned except the 3 x 3 chunks round this one. */
		void leaveAround(int chunkX, int chunkZ) {
			List<int[]> left = new ArrayList<>();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					left.add(new int[] {chunkX + dx, chunkZ + dz});
				}
			}
			leaveOnly(left.toArray(new int[0][]));
		}

		/** Everything scanned except these chunks. */
		void leaveOnly(int[]... chunks) {
			for (int cz = MansionFinder.LOW_CHUNK; cz <= MansionFinder.HIGH_CHUNK; cz++) {
				for (int cx = MansionFinder.LOW_CHUNK; cx <= MansionFinder.HIGH_CHUNK; cx++) {
					covered.add(MansionFinder.key(cx, cz));
				}
			}
			for (int[] c : chunks) {
				covered.remove(MansionFinder.key(c[0], c[1]));
			}
		}

		/** One game tick: the walker steers, the player moves, the server sends the chunks around the player. */
		void tick(HollowsWalker walker) {
			walker.tick(this, this, true);
			if (attacking) {                              // a drill breaks stone in a few ticks of holding the button on it
				long block = lookingAt();
				attackTicks = block == attackTarget ? attackTicks + 1 : 1;
				attackTarget = block;
				HollowsPath.Cell cell = block == Long.MIN_VALUE ? null : at(HollowsPath.x(block), HollowsPath.y(block), HollowsPath.z(block));
				if (attackTicks >= 3 && (cell == HollowsPath.Cell.STONE || cell == HollowsPath.Cell.GEM)) {
					broken.add(block);
					attackTicks = 0;
				}
			}
			if (forward) {
				double rad = Math.toRadians(yaw);
				double nx = x - Math.sin(rad) * 0.25, nz = z + Math.cos(rad) * 0.25;
				if (fits(nx, z)) {
					x = nx;
				}
				if (fits(x, nz)) {
					z = nz;
				}
			}
			int pcx = Math.floorDiv((int) Math.floor(x), 16), pcz = Math.floorDiv((int) Math.floor(z), 16);
			for (int dx = -reach; dx <= reach; dx++) {
				for (int dz = -reach; dz <= reach; dz++) {
					covered.add(MansionFinder.key(pcx + dx, pcz + dz));
				}
			}
			now += 50;
		}
	}

	private FakeGame game;
	private HollowsWalker walker;

	@BeforeEach
	void setUp() {
		game = new FakeGame();
		walker = new HollowsWalker(new java.util.Random(1));
	}

	@Test
	void walksToTheAreaLeftAndStopsWhenAllIsScanned() {
		game.leaveAround(24, 18);                         // 6 chunks east of the player, in the same quadrant
		walker.start(game, game);
		for (int i = 0; i < 4000 && walker.active(); i++) {
			game.tick(walker);
		}
		assertTrue(game.covered(24, 18));
		assertFalse(walker.active());
		assertTrue(game.said.get(game.said.size() - 1).contains("scanned"), game.said.toString());
		assertTrue(game.commands.isEmpty(), "no warp needed for a chunk this close");
		assertTrue(game.x > 330, "it walked east");
		assertTrue(game.hops > 0, "teleported along the open stretch");
	}

	@Test
	void followsTheTunnelsTheMapHasScannedRoundToAChunkBehindSolidStone() {
		HollowsTunnelsTest.Stone stone = HollowsTunnelsTest.uTunnel();
		stone.learnAll(game.tunnels);                    // the whole U was scanned before the walk started
		game.world = stone;
		game.x = 325.5;
		game.z = 325.5;
		game.leaveOnly(new int[] {27, 19}, new int[] {27, 20}, new int[] {27, 21},    // east of the start, behind solid
				new int[] {28, 19}, new int[] {28, 20}, new int[] {28, 21});          // stone: only reached round the U
		walker.start(game, game);
		for (int i = 0; i < 8000 && !game.covered(27, 20); i++) {
			game.tick(walker);
		}
		assertTrue(game.covered(27, 20), "got there at " + game.x + ", " + game.z + "; said " + game.said);
		assertTrue(game.x > 410, "came up the far arm of the U");
	}

	@Test
	void minesThroughAWallOfStoneWithTheDrill() {
		game.world = (x, y, z) -> y == 99 ? HollowsPath.Cell.SOLID
				: y < 99 ? HollowsPath.Cell.UNLOADED
				: x >= 310 && x <= 312 && y <= 104 ? HollowsPath.Cell.STONE    // a wall of stone too high to climb, all along
				: HollowsPath.Cell.OPEN;
		game.leaveAround(23, 18);                         // beyond the wall
		walker.start(game, game);
		for (int i = 0; i < 6000 && !game.covered(23, 18); i++) {
			game.tick(walker);
		}
		assertTrue(game.covered(23, 18), "got through at " + game.x + ", " + game.z);
		assertTrue(game.x > 312, "on the far side of the wall");
		assertFalse(game.broken.isEmpty(), "by mining");
		assertTrue(game.broken.size() <= 12, "a tunnel, not a quarry: " + game.broken.size() + " blocks");
	}

	@Test
	void warpsToTheNucleusForTheNextQuadrantWhenThisOneIsDone() {
		game.leaveAround(45, 45);                         // only the south-east has chunks left; the player is in the north-west
		walker.start(game, game);
		for (int i = 0; i < 40 && game.commands.isEmpty(); i++) {
			game.tick(walker);                            // it waits a moment for the chunks around before setting off
		}
		assertEquals(List.of(HollowsWalker.WARP_COMMAND), game.commands);

		game.x = 513;                                     // the warp lands you in the Nucleus
		game.z = 513;
		game.tick(walker);
		assertTrue(game.said.get(game.said.size() - 1).contains("scanning the south-east"), game.said.toString());
		game.tick(walker);
		assertEquals(1, game.commands.size(), "no second warp from inside the Nucleus");
	}

	@Test
	void waitsWhileAMenuIsOpen() {
		game.leaveOnly(new int[] {24, 18});
		walker.start(game, game);
		game.busy = true;
		int steers = game.steers;
		for (int i = 0; i < 20; i++) {
			game.tick(walker);
		}
		assertEquals(steers, game.steers);
		assertEquals(300.5, game.x);
		assertTrue(walker.active());
	}

	@Test
	void stopsAndLetsGoWhenYouLeaveTheHollows() {
		game.leaveOnly(new int[] {24, 18});
		walker.start(game, game);
		for (int i = 0; i < 20; i++) {
			game.tick(walker);
		}
		walker.tick(game, game, false);
		assertFalse(walker.active());
		assertFalse(game.forward);
		assertTrue(game.said.get(game.said.size() - 1).contains("left the Crystal Hollows"));
	}

	@Test
	void theKeySwitchesItOnAndOff() {
		game.leaveOnly(new int[] {24, 18});
		walker.toggle(game, game);
		assertTrue(walker.active());
		walker.toggle(game, game);
		assertFalse(walker.active());
		assertTrue(game.said.get(game.said.size() - 1).startsWith("off"));
	}

	@Test
	void startsFromTheBlockUnderAnEdgeOfThePlayerWhenTheMiddleHangsOverAir() {
		HollowsPath.Blocks ledge = (x, y, z) -> y == 99 && x <= 10 ? HollowsPath.Cell.SOLID
				: y < 99 ? HollowsPath.Cell.UNLOADED : HollowsPath.Cell.OPEN;
		long start = HollowsWalker.startBlock(ledge, 11.2, 100, 5.5);   // the middle is over air, the west side on the ledge
		assertEquals(10, HollowsPath.x(start));
		assertEquals(100, HollowsPath.y(start));
		assertEquals(Long.MIN_VALUE, HollowsWalker.startBlock(ledge, 12.5, 100, 5.5), "nothing under any side");
	}

	@Test
	void theAimNeverCutsAcrossAHole() {
		HollowsPath.Blocks holed = (x, y, z) -> y == 99 && x != 310 ? HollowsPath.Cell.SOLID
				: y < 99 ? HollowsPath.Cell.UNLOADED : HollowsPath.Cell.OPEN;
		assertTrue(HollowsWalker.groundAlong(holed, 305.5, 300.5, 100, 308.5, 301.5, 100));
		assertFalse(HollowsWalker.groundAlong(holed, 305.5, 300.5, 100, 314.5, 300.5, 100), "straight over the hole");
		HollowsPath.Blocks stepped = (x, y, z) -> y == 99 || (y == 100 && x >= 310) ? HollowsPath.Cell.SOLID
				: y < 99 ? HollowsPath.Cell.UNLOADED : HollowsPath.Cell.OPEN;
		assertTrue(HollowsWalker.groundAlong(stepped, 305.5, 300.5, 100, 312.5, 300.5, 101), "a step up is fine");
	}

	@Test
	void quadrantsAreRoundTheNucleus() {
		assertEquals(0, HollowsWalker.quadrantOf(13, 13));
		assertEquals(1, HollowsWalker.quadrantOf(50, 13));
		assertEquals(2, HollowsWalker.quadrantOf(50, 50));
		assertEquals(3, HollowsWalker.quadrantOf(13, 50));
	}

	@Test
	void sectorsGoRoundFromTheWest() {
		assertEquals(0, HollowsWalker.sectorOf(13, 32));  // west of the Nucleus
		assertEquals(2, HollowsWalker.sectorOf(32, 13));  // north
		assertEquals(4, HollowsWalker.sectorOf(50, 32));  // east
		assertEquals(6, HollowsWalker.sectorOf(32, 50));  // south
	}
}
