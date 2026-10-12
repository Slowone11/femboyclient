package com.femboyclient;

import java.util.Arrays;

/**
 * What Hollows Walk has learned of the Crystal Hollows' tunnels, kept after the chunks are unloaded, until you leave the lobby.
 *
 * Every chunk of the Hollows that loads is remembered as what each block is to a walking player (open, ground that cannot be mined,
 * stone, gemstone, a slab or stairs, or in the way), four bits a block: at most 40 x 40 chunks x 176 blocks of height, about 35 MB.
 * So a path can be planned through tunnels a little out of view, and what was mined stays known.
 */
final class HollowsTunnels implements HollowsPath.Blocks {

	static final int LOW_CHUNK = MansionFinder.LOW_CHUNK;
	static final int SPAN = MansionFinder.HIGH_CHUNK - MansionFinder.LOW_CHUNK + 1;
	static final int LOW_SECTION = 1;                         // y 16 up, the same layers as the scanner's
	static final int SECTIONS = MansionFinder.LAYERS;
	static final int MIN_Y = LOW_SECTION * 16, MAX_Y = (LOW_SECTION + SECTIONS) * 16 - 1;

	/** What a block is, as remembered. */
	static final byte UNKNOWN = 0, OPEN = 1, SOLID = 2, BLOCKED = 3, STONE = 4, GEM = 5, LOW = 6;
	private static final HollowsPath.Cell[] CELLS = new HollowsPath.Cell[16];

	static {
		Arrays.fill(CELLS, HollowsPath.Cell.UNLOADED);
		CELLS[OPEN] = HollowsPath.Cell.OPEN;
		CELLS[SOLID] = HollowsPath.Cell.SOLID;
		CELLS[BLOCKED] = HollowsPath.Cell.TALL;
		CELLS[STONE] = HollowsPath.Cell.STONE;
		CELLS[GEM] = HollowsPath.Cell.GEM;
		CELLS[LOW] = HollowsPath.Cell.LOW;
	}

	/** The code a cell is remembered as. */
	static byte code(HollowsPath.Cell cell) {
		return switch (cell) {
			case OPEN, WATER -> OPEN;
			case SOLID -> SOLID;
			case STONE -> STONE;
			case GEM -> GEM;
			case LOW -> LOW;
			case UNLOADED -> UNKNOWN;
			default -> BLOCKED;
		};
	}

	private final long[][] cells = new long[SPAN * SPAN * SECTIONS][];
	private final boolean[] recorded = new boolean[SPAN * SPAN];

	/**
	 * Remembers a chunk. Each section is 4096 codes ({@link #OPEN}...), indexed (y << 8) | (z << 4) | x, for the heights
	 * {@link #MIN_Y} up, one after the other; null is a section with nothing but air.
	 */
	void record(int chunkX, int chunkZ, byte[][] sections) {
		if (!inside(chunkX, chunkZ)) {
			return;
		}
		recorded[column(chunkX, chunkZ)] = true;
		for (int k = 0; k < SECTIONS; k++) {
			long[] packed = new long[256];
			byte[] codes = k < sections.length ? sections[k] : null;
			for (int i = 0; i < 4096; i++) {
				long code = codes == null ? OPEN : codes[i] & 15;
				packed[i >> 4] |= code << ((i & 15) * 4);
			}
			cells[index(chunkX, LOW_SECTION + k, chunkZ)] = packed;
		}
	}

	/** True when this chunk has been remembered in this lobby. */
	boolean recorded(int chunkX, int chunkZ) {
		return inside(chunkX, chunkZ) && recorded[column(chunkX, chunkZ)];
	}

	@Override
	public HollowsPath.Cell at(int x, int y, int z) {
		int chunkX = x >> 4, chunkZ = z >> 4;
		if (y < MIN_Y || y > MAX_Y || !inside(chunkX, chunkZ)) {
			return HollowsPath.Cell.UNLOADED;
		}
		long[] c = cells[index(chunkX, y >> 4, chunkZ)];
		if (c == null) {
			return HollowsPath.Cell.UNLOADED;
		}
		int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
		return CELLS[(int) (c[i >> 4] >>> ((i & 15) * 4)) & 15];
	}

	/** Forgets everything, for a new lobby. */
	void reset() {
		Arrays.fill(cells, null);
		Arrays.fill(recorded, false);
	}

	static boolean inside(int chunkX, int chunkZ) {
		return MansionFinder.inHollows(chunkX, chunkZ);
	}

	private static int column(int chunkX, int chunkZ) {
		return (chunkZ - LOW_CHUNK) * SPAN + (chunkX - LOW_CHUNK);
	}

	private static int index(int chunkX, int sy, int chunkZ) {
		return column(chunkX, chunkZ) * SECTIONS + (sy - LOW_SECTION);
	}
}
