package com.femboyclient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds Fairy Grottos and the Mansion in chunks the game has loaded, without walking into them.
 *
 * <b>Fairy Grottos</b> are found by their jasper (magenta stained glass and panes), which only they hold. Every loaded chunk goes
 * through a probe that only asks each section's block palette "is there jasper in you?", which costs a few microseconds and needs
 * no counting, so a whole burst of chunks is looked at in one tick. A chunk with jasper is then counted properly and located, and
 * the grotto is reported the same tick. Chunks are queued the moment the game loads them ({@link #chunkLoaded}), not found by a
 * sweep later.
 *
 * <b>The Mansion</b> is found by what it is made of (see {@link #setMansionCore}): the same probe asks each chunk's palettes which
 * of its kinds of block it holds. No other block counting is done.
 *
 * The game is reached through {@link Chunks}, so all of this is testable without Minecraft.
 */
final class MansionFinder {

	/** What the game shows of its loaded chunks. All methods run on the client thread. */
	interface Chunks {
		/** Chunk keys of the loaded chunks around a chunk, nearest first. */
		List<Long> loaded(int chunkX, int chunkZ, int radius);

		/** Identifies this chunk object, so a chunk that was unloaded and loaded again is scanned again. Null when not loaded. */
		Integer identity(int chunkX, int chunkZ);

		/** The quick question: is there any jasper in this chunk? Reads the block palettes only, never a block. */
		boolean hasJasper(int chunkX, int chunkZ);

		/** How many blocks of each kind are in the chunk (air left out), or null when it is not loaded. */
		Map<String, Integer> blocks(int chunkX, int chunkZ);

		/** The box {minX, minY, minZ, maxX, maxY, maxZ} around the blocks of these kinds in the chunk, or null. */
		int[] locate(int chunkX, int chunkZ, Set<String> kinds);

		/**
		 * The jasper of the chunk cut into bands by height, each {minX, minY, minZ, maxX, maxY, maxZ, jasper count}. Two grottos stacked
		 * in one column of chunks are two bands. A game that cannot tell gives the one box of {@link #locate}, without a count.
		 */
		default List<int[]> locateBands(int chunkX, int chunkZ, Set<String> kinds) {
			int[] box = locate(chunkX, chunkZ, kinds);
			return box == null ? List.of() : List.of(box);
		}

		/** Which of these kinds of block the chunk holds, told from the sections' block palettes (a few microseconds), never a block. */
		default Set<String> present(int chunkX, int chunkZ, Set<String> kinds) {
			Map<String, Integer> all = blocks(chunkX, chunkZ);
			if (all == null) {
				return Set.of();
			}
			Set<String> out = new HashSet<>(all.keySet());
			out.retainAll(kinds);
			return out;
		}

		/** The kinds of block in the chunk between these heights (whole 16 block sections that touch them). */
		default Set<String> kindsBetween(int chunkX, int chunkZ, int lowY, int highY) {
			Map<String, Integer> all = blocks(chunkX, chunkZ);
			return all == null ? Set.of() : all.keySet();
		}

		/** Which {@link #LAYERS} heights of the chunk hold blocks, one bit each (bit 0 is y 16..31). Reads the sections only. */
		default int layers(int chunkX, int chunkZ) {
			return (1 << LAYERS) - 1;
		}
	}

	/**
	 * A recognised structure: what it is, an id that stays the same while it grows (so a later report replaces an earlier one), and
	 * the box {minX, minY, minZ, maxX, maxY, maxZ}. For a Fairy Grotto also how many jasper blocks were counted in it and its rare
	 * block kinds; for the others 0 and none.
	 */
	interface Found {
		void found(String structure, int id, int[] box, int jasper, Set<String> kinds);
	}

	/** The Hollows are cut into this many layers of 16 blocks, from y 16 up (layer 0 is y 16..31, layer 10 is y 176..191). */
	static final int LAYERS = 11;

	static int layerOf(int y) {
		return (y >> 4) - 1;
	}

	static final String GROTTO = "Fairy Grotto";

	/**
	 * Jasper, the gemstone that only Fairy Grottos hold, is magenta stained glass and panes. Nothing has to be learned for
	 * this one: of the chunks scanned so far, under 1% hold it, which is what three small grottos in a lobby look like.
	 */
	static final Set<String> JASPER = Set.of("magenta_stained_glass", "magenta_stained_glass_pane");

	static final int PROBE_PER_TICK = 2048;            // most chunks probed in one tick; a probe is a few microseconds, so time is the real limit
	static final long PROBE_BUDGET_NANOS = 4_000_000L; // and the most time they may take (a tick is 50 ms)
	static final int SWEEP_TICKS = 20;                 // the loaded chunks are checked for missed ones this often,
	static final int FAST_SWEEP_TICKS = 4;             // and this often for the first FAST_PHASE_TICKS after entering, when chunks pour in
	static final int FAST_PHASE_TICKS = 20 * 15;
	static final int RADIUS_LIMIT = 32;                // chunks; the game's render distance and the server's view distance limit it further
	static final int CLUSTER_CHUNKS = 3;               // chunks this close belong to the same structure
	static final int BAND_GAP = 10;                    // jasper this far apart in height, in one chunk, is two grottos stacked on each other
	static final int Y_JOIN = 10;                      // and pieces of grotto this close in height (and close in x and z) are one grotto

	/** The Crystal Hollows span about 202..823 in x and z. Chunks outside that are never counted. */
	static final int LOW_CHUNK = 12, HIGH_CHUNK = 51;

	private static int nextId = 1;

	private static final class GrottoChunk {
		int chunkX, chunkZ;
		int jasper;
		Set<String> kinds;
		int[] box;
	}

	private static final class Grotto {
		final List<GrottoChunk> chunks = new ArrayList<>();
		int[] boxReported;
		int jasperReported = -1;
		int kindsReported = -1;
		final int id = nextId++;
	}

	private final Map<Long, Integer> probed = new HashMap<>();
	private final Set<Long> queuedProbe = new HashSet<>();
	private final ArrayDeque<Long> probeQueue = new ArrayDeque<>();
	private final List<Grotto> grottos = new ArrayList<>();
	private final Set<Grotto> dirty = new LinkedHashSet<>();

	/**
	 * A stretch of chunks that holds the core kinds of the Mansion. The Mansion is found by what it is made of, not by its jasper: its
	 * jasper veins can be broken up by another structure that spawned into it, but its blocks are still there. Chunks that hold any
	 * of the core kinds are gathered, and when the ones close together hold enough of the different kinds, that is a Mansion.
	 */
	private static final class Spot {
		final Map<Long, Set<String>> kinds = new HashMap<>();
		final Map<Long, int[]> boxes = new HashMap<>();
		int[] boxReported;
		int kindsReported = -1;
		final int id = nextId++;
	}

	private final List<Spot> spots = new ArrayList<>();
	private final Set<Spot> spotsDirty = new LinkedHashSet<>();
	private volatile Set<String> mansionCore = Set.of();
	private volatile int mansionNeed;
	private volatile Set<String> mansionExclude = Set.of();
	private Set<String> coreUsed = Set.of();

	private volatile Set<String> distinctive = Set.of();
	private int sinceSweep = SWEEP_TICKS;
	private int sinceEntering;
	private volatile int loadedChunks, reach;

	/** Every chunk of this lobby that has been looked at, kept until you leave it (unlike {@link #probed}, which starts over). */
	private static final int SPAN = HIGH_CHUNK - LOW_CHUNK + 1;
	private final boolean[] covered = new boolean[SPAN * SPAN];
	private final int[] layerMask = new int[SPAN * SPAN];
	private volatile int coveredCount, version;

	private static int index(int chunkX, int chunkZ) {
		return (chunkZ - LOW_CHUNK) * SPAN + (chunkX - LOW_CHUNK);
	}

	/** True when this chunk of the Hollows has been scanned in this lobby. */
	boolean isCovered(int chunkX, int chunkZ) {
		return inHollows(chunkX, chunkZ) && covered[index(chunkX, chunkZ)];
	}

	/** The heights of this chunk that have been scanned in this lobby, one bit per layer. 0 when none. */
	int layers(int chunkX, int chunkZ) {
		return inHollows(chunkX, chunkZ) ? layerMask[index(chunkX, chunkZ)] : 0;
	}

	/** How many chunks of the Hollows have been scanned in this lobby. */
	int coveredCount() {
		return coveredCount;
	}

	/** Goes up whenever something new has been scanned, so the map knows when to draw the scanned ground again. */
	int version() {
		return version;
	}

	/** How many chunks the whole Hollows square holds, to compare the count with. */
	static int totalChunks() {
		return SPAN * SPAN;
	}

	private void markCovered(int chunkX, int chunkZ, int layers) {
		if (!inHollows(chunkX, chunkZ)) {
			return;
		}
		int i = index(chunkX, chunkZ);
		if (!covered[i]) {
			covered[i] = true;
			coveredCount++;
			version++;
		}
		int mask = layers & ((1 << LAYERS) - 1);
		if ((layerMask[i] | mask) != layerMask[i]) {
			layerMask[i] |= mask;
			version++;
		}
	}

	/** How many chunks of the Hollows the game had loaded at the last sweep. */
	int loadedChunks() {
		return loadedChunks;
	}

	/** How far from you, in chunks, the farthest loaded chunk was at the last sweep: what the server actually sends. */
	int reach() {
		return reach;
	}

	static long key(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
	}

	static boolean inHollows(int chunkX, int chunkZ) {
		return chunkX >= LOW_CHUNK && chunkX <= HIGH_CHUNK && chunkZ >= LOW_CHUNK && chunkZ <= HIGH_CHUNK;
	}

	/**
	 * The core kinds of the Mansion, how many of them a stretch of chunks must hold to be one, and the kinds only other builds have
	 * (see {@link HollowsSync.MansionInfo}). Nothing is looked for until this is set. Chunks are looked at again when it changes.
	 */
	void setMansionCore(Set<String> core, int need, Set<String> exclude) {
		mansionNeed = need;
		mansionExclude = Set.copyOf(exclude);
		mansionCore = Set.copyOf(core);
	}

	void setMansionCore(Set<String> core, int need) {
		setMansionCore(core, need, Set.of());
	}

	/** The kinds of block reported for a grotto found by its jasper: the ones that tell whether it is the Mansion. */
	void setDistinctive(Set<String> kinds) {
		distinctive = Set.copyOf(kinds);
	}

	/** The game has just loaded this chunk: it goes to the front of the line, so it is probed on the next tick. */
	void chunkLoaded(int chunkX, int chunkZ) {
		if (inHollows(chunkX, chunkZ)) {
			long key = key(chunkX, chunkZ);
			if (queuedProbe.add(key)) {
				probeQueue.addFirst(key);
			}
		}
	}

	/**
	 * Call every client tick while in the Crystal Hollows.
	 *
	 * @param playerX  where the player is, in blocks
	 */
	void tick(Chunks chunks, int playerX, int playerZ, Found found) {
		Set<String> core = mansionCore;
		if (!core.equals(coreUsed)) {
			coreUsed = core;
			probed.clear();
			spots.clear();
			spotsDirty.clear();
			sinceSweep = SWEEP_TICKS;
		}

		int playerChunkX = Math.floorDiv(playerX, 16), playerChunkZ = Math.floorDiv(playerZ, 16);

		// Chunks the game announced are already in line. Now and then the loaded chunks are checked for any that were missed.
		sinceEntering++;
		if (++sinceSweep >= (sinceEntering < FAST_PHASE_TICKS ? FAST_SWEEP_TICKS : SWEEP_TICKS)) {
			sinceSweep = 0;
			int count = 0, farthest = 0;
			for (long key : chunks.loaded(playerChunkX, playerChunkZ, RADIUS_LIMIT)) {
				int chunkX = (int) (key >> 32), chunkZ = (int) key;
				farthest = Math.max(farthest, Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ)));
				if (!inHollows(chunkX, chunkZ)) {
					continue;
				}
				count++;
				Integer identity = chunks.identity(chunkX, chunkZ);
				if (identity != null && !identity.equals(probed.get(key)) && queuedProbe.add(key)) {
					probeQueue.addLast(key);
				}
			}
			loadedChunks = count;
			reach = farthest;
		}

		// The fast pass: every chunk in line is asked the one question. Time is the limit, not a fixed number of chunks.
		long stop = System.nanoTime() + PROBE_BUDGET_NANOS;
		for (int n = 0; n < PROBE_PER_TICK && !probeQueue.isEmpty() && System.nanoTime() < stop; n++) {
			long key = probeQueue.pollFirst();
			queuedProbe.remove(key);
			int chunkX = (int) (key >> 32), chunkZ = (int) key;
			Integer identity = chunks.identity(chunkX, chunkZ);
			if (identity == null || identity.equals(probed.get(key))) {
				continue;                                 // unloaded since, or already looked at
			}
			probed.put(key, identity);
			markCovered(chunkX, chunkZ, chunks.layers(chunkX, chunkZ));
			if (!core.isEmpty()) {
				spot(chunks, chunkX, chunkZ, core);
			}
			if (chunks.hasJasper(chunkX, chunkZ)) {
				Map<String, Integer> blocks = chunks.blocks(chunkX, chunkZ);
				if (blocks != null) {
					lookForJasper(chunks, chunkX, chunkZ, blocks);
				}
			}
		}

		for (Spot spot : spotsDirty) {
			reportSpot(spot, found);
		}
		spotsDirty.clear();
		for (Grotto grotto : dirty) {
			reportGrotto(grotto, found);
		}
		dirty.clear();
	}

	/** Notes which core kinds of the Mansion this chunk holds, and joins it to the stretch of chunks it is close to. */
	private void spot(Chunks chunks, int chunkX, int chunkZ, Set<String> core) {
		long key = key(chunkX, chunkZ);
		for (Spot spot : spots) {
			if (spot.kinds.remove(key) != null) {
				spot.boxes.remove(key);
				spotsDirty.add(spot);
			}
		}
		Set<String> both = new HashSet<>(core);
		both.addAll(mansionExclude);
		Set<String> here = chunks.present(chunkX, chunkZ, both);
		if (here.isEmpty()) {
			return;
		}
		Set<String> coreHere = new HashSet<>(here);
		coreHere.retainAll(core);
		int[] box = chunks.locate(chunkX, chunkZ, coreHere.isEmpty() ? here : coreHere);
		if (box == null) {
			return;
		}
		Spot home = null;
		for (Spot spot : spots) {
			for (long other : spot.kinds.keySet()) {
				if (Math.max(Math.abs((int) (other >> 32) - chunkX), Math.abs((int) other - chunkZ)) <= CLUSTER_CHUNKS) {
					home = spot;
					break;
				}
			}
			if (home != null) {
				break;
			}
		}
		if (home == null) {
			home = new Spot();
			spots.add(home);
		}
		home.kinds.put(key, new HashSet<>(here));
		home.boxes.put(key, box);
		spotsDirty.add(home);
	}

	/**
	 * True when some jasper has been found within reach of these chunks. The Mansion is a Fairy Grotto, so it always has jasper somewhere,
	 * even when its veins are broken up; the same blocks in the plain terrain (stone bricks and flowers grow there too) are not one.
	 */
	private boolean nearJasper(Spot spot) {
		for (long key : spot.kinds.keySet()) {
			if (jasperNear((int) (key >> 32), (int) key)) {
				return true;
			}
		}
		return false;
	}

	private boolean jasperNear(int chunkX, int chunkZ) {
		for (Grotto grotto : grottos) {
			for (GrottoChunk other : grotto.chunks) {
				if (Math.max(Math.abs(other.chunkX - chunkX), Math.abs(other.chunkZ - chunkZ)) <= CLUSTER_CHUNKS) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Reports the part of a stretch of chunks that is a Mansion, as a Fairy Grotto with no jasper: the Mansion, found by its blocks. A
	 * Mansion has its core kinds together, in every chunk of it, so a group of 2 x 2 chunks that holds enough of them is one. Kinds
	 * spread over a wider area are not: jungle has the same flowers and a temple has the same stone bricks, a few chunks apart.
	 */
	private void reportSpot(Spot spot, Found found) {
		int need = mansionNeed;
		if (need <= 0 || !nearJasper(spot)) {
			return;
		}
		Set<Long> inside = new HashSet<>();
		for (long key : spot.kinds.keySet()) {
			int cx = (int) (key >> 32), cz = (int) key;
			for (int ox = cx - 1; ox <= cx; ox++) {
				for (int oz = cz - 1; oz <= cz; oz++) {
					Set<String> together = new HashSet<>();
					List<Long> members = new ArrayList<>();
					for (int dx = 0; dx <= 1; dx++) {
						for (int dz = 0; dz <= 1; dz++) {
							Set<String> one = spot.kinds.get(key(ox + dx, oz + dz));
							if (one != null) {
								together.addAll(one);
								members.add(key(ox + dx, oz + dz));
							}
						}
					}
					Set<String> coreTogether = new HashSet<>(together);
					coreTogether.retainAll(mansionCore);
					if (coreTogether.size() >= need && together.stream().noneMatch(mansionExclude::contains)) {
						inside.addAll(members);
					}
				}
			}
		}
		Set<String> all = new HashSet<>();
		int[] box = null;
		for (long key : inside) {
			all.addAll(spot.kinds.get(key));
			int[] one = spot.boxes.get(key);
			if (box == null) {
				box = one.clone();
			} else {
				for (int i = 0; i < 3; i++) {
					box[i] = Math.min(box[i], one[i]);
					box[i + 3] = Math.max(box[i + 3], one[i + 3]);
				}
			}
		}
		if (box == null || (Arrays.equals(box, spot.boxReported) && all.size() == spot.kindsReported)) {
			return;
		}
		spot.boxReported = box;
		spot.kindsReported = all.size();
		found.found(GROTTO, spot.id, box, 0, all);
	}

	/** How far apart two boxes are in height, 0 when they touch or overlap. */
	private static int heightGap(int[] a, int[] b) {
		return Math.max(0, Math.max(a[1] - b[4], b[1] - a[4]));
	}

	/**
	 * A chunk with jasper in it is part of a Fairy Grotto. Its jasper is cut into bands by height, and each band goes to the grotto it
	 * is close to in all three directions. So a grotto stacked above another is its own grotto, not part of the one below.
	 */
	private void lookForJasper(Chunks chunks, int chunkX, int chunkZ, Map<String, Integer> blocks) {
		int total = 0;
		for (String kind : JASPER) {
			total += blocks.getOrDefault(kind, 0);
		}
		if (total == 0) {
			return;
		}
		// The chunk is looked at again (it was unloaded and loaded): what it gave before is replaced.
		for (Grotto grotto : grottos) {
			if (grotto.chunks.removeIf(c -> c.chunkX == chunkX && c.chunkZ == chunkZ)) {
				dirty.add(grotto);
			}
		}
		List<int[]> bands = chunks.locateBands(chunkX, chunkZ, JASPER);
		for (int[] band : bands) {
			Grotto home = null;
			for (Grotto grotto : grottos) {
				for (GrottoChunk other : grotto.chunks) {
					if (Math.max(Math.abs(other.chunkX - chunkX), Math.abs(other.chunkZ - chunkZ)) <= CLUSTER_CHUNKS
							&& heightGap(band, other.box) <= Y_JOIN) {
						home = grotto;
						break;
					}
				}
				if (home != null) {
					break;
				}
			}
			if (home == null) {
				home = new Grotto();
				grottos.add(home);
			}

			GrottoChunk info = new GrottoChunk();
			info.chunkX = chunkX;
			info.chunkZ = chunkZ;
			info.jasper = band.length > 6 ? band[6] : total;
			info.box = java.util.Arrays.copyOf(band, 6);
			info.kinds = new HashSet<>(chunks.kindsBetween(chunkX, chunkZ, band[1], band[4]));
			home.chunks.add(info);
			dirty.add(home);
			for (Spot spot : spots) {                      // jasper turned up near a stretch of chunks that was waiting for some
				for (long key : spot.kinds.keySet()) {
					if (Math.max(Math.abs((int) (key >> 32) - chunkX), Math.abs((int) key - chunkZ)) <= CLUSTER_CHUNKS) {
						spotsDirty.add(spot);
						break;
					}
				}
			}
		}
	}

	/** Sends a grotto when it is new or has grown: more of its box, more jasper, or more of its telltale block kinds. */
	private void reportGrotto(Grotto grotto, Found found) {
		int[] box = null;
		int jasper = 0;
		Set<String> kinds = new HashSet<>();
		Set<String> distinctiveNow = distinctive;
		for (GrottoChunk info : grotto.chunks) {
			jasper += info.jasper;
			for (String kind : info.kinds) {
				if (distinctiveNow.contains(kind)) {
					kinds.add(kind);
				}
			}
			if (box == null) {
				box = info.box.clone();
			} else {
				for (int i = 0; i < 3; i++) {
					box[i] = Math.min(box[i], info.box[i]);
					box[i + 3] = Math.max(box[i + 3], info.box[i + 3]);
				}
			}
		}
		if (box == null || (Arrays.equals(box, grotto.boxReported) && jasper == grotto.jasperReported
				&& kinds.size() == grotto.kindsReported)) {
			return;
		}
		grotto.boxReported = box;
		grotto.jasperReported = jasper;
		grotto.kindsReported = kinds.size();
		found.found(GROTTO, grotto.id, box, jasper, kinds);
	}

	/** What was scanned in a lobby, kept in memory until the game closes so hopping back shows it again. */
	private record Saved(boolean[] covered, int[] layerMask) {
	}

	private static final int MAX_SAVED = 64;
	private final Map<String, Saved> saved = new java.util.LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Saved> eldest) {
			return size() > MAX_SAVED;
		}
	};
	private String activeKey;

	private void saveActive() {
		if (activeKey != null && coveredCount > 0) {
			saved.put(activeKey, new Saved(covered.clone(), layerMask.clone()));
		}
	}

	/** The lobby is now named: bring back what was scanned in it before, and keep what was scanned while it had no name. */
	void useLobby(String key) {
		if (key == null || key.equals(activeKey)) {
			return;
		}
		saveActive();
		Saved old = saved.get(key);
		if (old != null) {
			int count = 0;
			for (int i = 0; i < covered.length; i++) {
				covered[i] |= old.covered()[i];
				layerMask[i] |= old.layerMask()[i];
				if (covered[i]) {
					count++;
				}
			}
			coveredCount = count;
			version++;
		}
		activeKey = key;
	}

	/** Forgets the lobby: every chunk is new in the next one. */
	void reset() {
		saveActive();
		activeKey = null;
		probed.clear();
		queuedProbe.clear();
		probeQueue.clear();
		grottos.clear();
		dirty.clear();
		spots.clear();
		spotsDirty.clear();
		sinceSweep = SWEEP_TICKS;
		sinceEntering = 0;
		loadedChunks = 0;
		reach = 0;
		Arrays.fill(covered, false);
		Arrays.fill(layerMask, 0);
		coveredCount = 0;
		version++;
	}
}
