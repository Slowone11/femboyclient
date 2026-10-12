package com.femboyclient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A walking path through the loaded blocks, for {@link HollowsWalker}. A* over block positions: a step is a walk to one of the 8
 * blocks around, a jump one block up, or a drop of up to {@link #MAX_DROP} blocks down.
 *
 * Normally nothing is mined, so a cave that is sealed off is simply not reached. A search that may dig also steps through stone and
 * gemstone (straight ahead, or one block up), at a cost of {@link #DIG_STONE} or {@link #DIG_GEM} for each block to break, so it
 * only digs where walking round is much longer, and it goes round what cannot be broken (the blocks of a structure).
 *
 * The search runs a little each tick ({@link #step}), so a long one never stalls the game. When the goal cannot be reached within
 * {@link #MAX_NODES} positions, the path goes to the position that came closest to it, and the walker plans again from there.
 *
 * The game is reached through {@link Blocks}, so all of this is testable without Minecraft.
 */
final class HollowsPath {

	/** What a block is to a walking player. */
	enum Cell {
		/** Nothing to bump into: air, flowers, vines. */
		OPEN,
		/** Water: passable, but slow. */
		WATER,
		/** Something to stand on, at most one block tall, that cannot be mined. */
		SOLID,
		/** Stone, ore, mithril or titanium: something to stand on that a drill breaks quickly. */
		STONE,
		/** Gemstone: something to stand on that takes a while to break. */
		GEM,
		/** A slab, stairs or a carpet: something to stand on, half a block high or less, walked onto without a jump. */
		LOW,
		/** Taller than a block (a fence, a wall): it blocks the way and cannot be stood on from the block above. */
		TALL,
		/** Hurts: lava, fire, magma, cactus. Never walked into or stood on. */
		DANGER,
		/** Not loaded, or outside the world. Treated as a wall. */
		UNLOADED
	}

	/** The blocks of the world. Client thread only. */
	interface Blocks {
		Cell at(int x, int y, int z);
	}

	/** Where the path should go. */
	interface Goal {
		/** How far this position is from the goal, in blocks, never more than the real walk. 0 means the goal is reached. */
		double distance(int x, int y, int z);
	}

	static final int MAX_DROP = 4;                // blocks a path may fall in one step
	static final int MAX_NODES = 40_000;          // positions looked at before the search settles for the closest one
	static final double JUMP_COST = 1.5;          // a jump costs a little more than a flat step
	static final double WATER_COST = 3;           // and a step in water a lot more
	static final double STEP_HEIGHT = 0.6;        // a rise up to this much is walked up (slabs, stairs), more needs a jump,
	static final double MAX_RISE = 1.25;          // and more than this cannot be jumped
	static final double DIG_STONE = 4;            // breaking a block of stone costs as much as walking this many blocks, so it
	static final double DIG_GEM = 10;             // follows a cave going the right way and only digs past a long way round
	static final double LEAN = 1.5;               // the distance left counts this much more than the way so far: straighter, longer plans

	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private record Node(long pos, double g, double f) {
	}

	/** One step a player can take: to here, at this cost. One way when it is a drop of more than a block, which cannot be climbed back. */
	interface Move {
		void to(int x, int y, int z, double cost, boolean oneWay);
	}

	private final Blocks blocks;
	private final Goal goal;
	private final boolean dig;
	private final long start;
	private final Map<Long, Cell> cells = new HashMap<>();
	private final Map<Long, Double> cost = new HashMap<>();
	private final Map<Long, Long> from = new HashMap<>();
	private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
	private long best;
	private double bestDistance;
	private int visited;
	private boolean done;

	/** Starts a search from where a player stands at this block (the block the feet are in), walking only. */
	HollowsPath(Blocks world, int x, int y, int z, Goal goal) {
		this(world, x, y, z, goal, false);
	}

	/** Starts a search that may also dig through stone and gemstone. */
	HollowsPath(Blocks world, int x, int y, int z, Goal goal, boolean dig) {
		this.blocks = (bx, by, bz) -> cells.computeIfAbsent(pack(bx, by, bz), p -> world.at(bx, by, bz));
		this.goal = goal;
		this.dig = dig;
		long startPos = standingSpot(x, y, z);
		this.start = startPos;
		this.best = startPos;
		if (startPos == Long.MIN_VALUE) {
			done = true;
			return;
		}
		bestDistance = goal.distance(x(startPos), y(startPos), z(startPos));
		cost.put(startPos, 0.0);
		open.add(new Node(startPos, 0, bestDistance));
	}

	/**
	 * The block the player stands in, or the one just above or below it: on a slab or a carpet the feet are inside a block that
	 * cannot be stood in, and in mid-jump they are above the ground. {@link Long#MIN_VALUE} when none of them is a place to stand.
	 */
	private long standingSpot(int x, int y, int z) {
		int found = standingY(blocks, x, y, z);
		return found == Integer.MIN_VALUE ? Long.MIN_VALUE : pack(x, found, z);
	}

	/** The height a player at this block stands at: this one, or just above or below it. {@link Integer#MIN_VALUE} when none. */
	static int standingY(Blocks blocks, int x, int y, int z) {
		for (int dy : new int[] {0, 1, -1, -2}) {
			if (standable(blocks, x, y + dy, z)) {
				return y + dy;
			}
		}
		return Integer.MIN_VALUE;
	}

	/** Looks at up to this many more positions. True once the search is over (goal reached, nothing left, or out of positions). */
	boolean step(int nodes) {
		for (int n = 0; n < nodes && !done; n++) {
			Node node = open.poll();
			if (node == null || visited >= MAX_NODES) {
				done = true;
				break;
			}
			if (node.g > cost.getOrDefault(node.pos, Double.MAX_VALUE)) {
				continue;                                 // a cheaper way here was found after this one was queued
			}
			visited++;
			int x = x(node.pos), y = y(node.pos), z = z(node.pos);
			double distance = goal.distance(x, y, z);
			if (distance < bestDistance || (distance == bestDistance && node.g < cost.get(best))) {
				best = node.pos;
				bestDistance = distance;
			}
			if (distance <= 0) {
				done = true;
				break;
			}
			expand(node, x, y, z);
		}
		return done;
	}

	boolean done() {
		return done;
	}

	/** True when the path ends at the goal, not just as close as the search got. */
	boolean reachesGoal() {
		return done && bestDistance <= 0;
	}

	/** How many positions were looked at. */
	int visited() {
		return visited;
	}

	/**
	 * The path from the start to the goal, or to the position closest to it, as packed positions ({@link #pack}), the start left
	 * out. Empty when the search has not got anywhere closer than the start. Only once {@link #done}.
	 */
	List<Long> path() {
		List<Long> out = new ArrayList<>();
		if (start == Long.MIN_VALUE || best == start) {
			return out;
		}
		for (long pos = best; pos != start; pos = from.get(pos)) {
			out.add(pos);
		}
		java.util.Collections.reverse(out);
		return out;
	}

	private void expand(Node node, int x, int y, int z) {
		moves(blocks, x, y, z, dig, (nx, ny, nz, step, oneWay) -> add(node, nx, ny, nz, step));
	}

	/** Every step a player standing here can take: walk to one of the 8 blocks around, jump one block up, or drop down. */
	static void moves(Blocks blocks, int x, int y, int z, Move move) {
		moves(blocks, x, y, z, false, move);
	}

	/** The same, and when it may dig, also through stone and gemstone: straight ahead, or one block up. */
	static void moves(Blocks blocks, int x, int y, int z, boolean dig, Move move) {
		double here = standHeight(blocks, x, y, z);
		for (int[] dir : DIRS) {
			int dx = dir[0], dz = dir[1];
			int nx = x + dx, nz = z + dz;
			if (dx != 0 && dz != 0) {
				// Cutting a corner needs both blocks beside it free, or the player catches on the edge.
				if (clear(blocks, x + dx, y, z) && clear(blocks, x, y, z + dz) && standable(blocks, nx, y, nz)) {
					move.to(nx, y, nz, Math.sqrt(2), false);
				}
				continue;
			}
			if (standable(blocks, nx, y, nz)) {
				move.to(nx, y, nz, 1, false);
			} else if (standable(blocks, nx, y + 1, nz)) {
				// Up onto the next block: walked up a slab or stairs, jumped onto a full block (room above your head needed).
				double rise = standHeight(blocks, nx, y + 1, nz) - here;
				boolean jump = rise > STEP_HEIGHT;
				if (rise <= MAX_RISE && (!jump || passable(blocks, x, y + 2, z))) {
					move.to(nx, y + 1, nz, jump ? JUMP_COST : 1, false);
				}
			} else if (clear(blocks, nx, y, nz)) {
				for (int drop = 1; drop <= MAX_DROP; drop++) {
					if (standable(blocks, nx, y - drop, nz)) {
						double fall = here - standHeight(blocks, nx, y - drop, nz);
						move.to(nx, y - drop, nz, 1 + drop * 0.5, drop > 1 || fall > MAX_RISE);
						break;
					}
					if (!passable(blocks, nx, y - drop, nz)) {
						break;
					}
				}
			} else if (dig) {
				// Through the wall: break what is in the way of feet and head, the ground stays.
				if (ground(blocks.at(nx, y - 1, nz)) && room(blocks, nx, y, nz) && room(blocks, nx, y + 1, nz)) {
					move.to(nx, y, nz, 1 + digCost(blocks, nx, y, nz) + digCost(blocks, nx, y + 1, nz), false);
				}
				// Up a block: the block above your head too, so there is room to jump.
				if (ground(blocks.at(nx, y, nz)) && room(blocks, x, y + 2, z) && room(blocks, nx, y + 1, nz)
						&& room(blocks, nx, y + 2, nz)) {
					move.to(nx, y + 1, nz, JUMP_COST + digCost(blocks, x, y + 2, z) + digCost(blocks, nx, y + 1, nz)
							+ digCost(blocks, nx, y + 2, nz), false);
				}
			}
		}
	}

	/** Something to stand on: solid, stone, gemstone, or a slab or stairs. */
	static boolean ground(Cell cell) {
		return cell == Cell.SOLID || cell == Cell.STONE || cell == Cell.GEM || cell == Cell.LOW;
	}

	/** How high the top of something to stand on is: half a block for a slab or stairs, else a whole block. */
	static double top(Cell cell) {
		return cell == Cell.LOW ? 0.5 : 1;
	}

	/** How high a player standing at this block really stands: on a slab, half a block lower than the block says. */
	static double standHeight(Blocks blocks, int x, int y, int z) {
		return y - 1 + top(blocks.at(x, y - 1, z));
	}

	/** The block a player whose feet are this high stands in: on a slab (feet at .5), the block above the slab. */
	static int feetBlock(double y) {
		return (int) Math.ceil(y - 0.05);
	}

	/** Stone or gemstone that can be broken without letting lava or water in, from above or the side. */
	static boolean diggable(Blocks blocks, int x, int y, int z) {
		Cell cell = blocks.at(x, y, z);
		if (cell != Cell.STONE && cell != Cell.GEM) {
			return false;
		}
		return !flows(blocks.at(x, y + 1, z)) && !flows(blocks.at(x + 1, y, z)) && !flows(blocks.at(x - 1, y, z))
				&& !flows(blocks.at(x, y, z + 1)) && !flows(blocks.at(x, y, z - 1));
	}

	private static boolean flows(Cell cell) {
		return cell == Cell.DANGER || cell == Cell.WATER;
	}

	/** Room for a player here once it has been dug out, if it needs to be. */
	private static boolean room(Blocks blocks, int x, int y, int z) {
		return passable(blocks, x, y, z) || diggable(blocks, x, y, z);
	}

	private static double digCost(Blocks blocks, int x, int y, int z) {
		Cell cell = blocks.at(x, y, z);
		return cell == Cell.STONE ? DIG_STONE : cell == Cell.GEM ? DIG_GEM : 0;
	}

	private void add(Node node, int x, int y, int z, double step) {
		long pos = pack(x, y, z);
		double g = node.g + step * (blocks.at(x, y, z) == Cell.WATER ? WATER_COST : 1);
		if (g < cost.getOrDefault(pos, Double.MAX_VALUE)) {
			cost.put(pos, g);
			from.put(pos, node.pos);
			open.add(new Node(pos, g, g + LEAN * goal.distance(x, y, z)));
		}
	}

	/** Feet and head both fit here: the two blocks a player takes up. */
	static boolean clear(Blocks blocks, int x, int y, int z) {
		return passable(blocks, x, y, z) && passable(blocks, x, y + 1, z);
	}

	/** A player can stand here: room for feet and head, and solid ground under the feet. */
	static boolean standable(Blocks blocks, int x, int y, int z) {
		return clear(blocks, x, y, z) && ground(blocks.at(x, y - 1, z));
	}

	static boolean passable(Blocks blocks, int x, int y, int z) {
		Cell c = blocks.at(x, y, z);
		return c == Cell.OPEN || c == Cell.WATER;
	}

	// Positions packed into one long the way Minecraft's BlockPos does it: 26 bits of x, 26 of z, 12 of y.

	static long pack(int x, int y, int z) {
		return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
	}

	static int x(long pos) {
		return (int) (pos >> 38);
	}

	static int y(long pos) {
		return (int) (pos << 52 >> 52);
	}

	static int z(long pos) {
		return (int) (pos << 26 >> 38);
	}
}
