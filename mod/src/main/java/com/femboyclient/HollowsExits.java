package com.femboyclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ways out of the Crystal Nucleus, learned by watching you walk them. The Nucleus is the same in every lobby, and its tunnels
 * are hard to find from inside it, so Hollows Walk follows a way someone has actually walked.
 *
 * Every tick in the Hollows the player's position is handed in ({@link #observe}). A trail starts where "warp cn" puts you (the
 * last time you were within {@link #SPAWN_RADIUS} blocks of it) and ends {@link #OUTSIDE} blocks from the middle of the Nucleus.
 * So it is the whole way out, down the middle and up the stairs, and can be walked exactly as it was. The shortest trail out in each of the
 * eight directions is kept, in a file, so it lasts across games and lobbies.
 */
final class HollowsExits {

	static final double SPAWN_X = 513.5, SPAWN_Z = 526.5;   // where "warp cn" puts you
	static final double SPAWN_RADIUS = 4;         // this close to it, a trail starts (again)
	static final double OUTSIDE = 80;             // and a trail is done this far from the middle
	static final double STEP = 1.5;               // a point is added to the trail every this many blocks
	static final double TELEPORT = 6;             // a move this big in one tick is a warp, not a walk
	static final int MAX_POINTS = 400;

	private static final Logger LOGGER = LoggerFactory.getLogger(FemboyClientMod.MOD_ID);

	private final Path file;
	private final java.util.function.Consumer<String> say;
	private final Map<Integer, List<Long>> trails = new HashMap<>();
	private List<Long> current;
	private double lastX, lastY, lastZ;
	private boolean loaded;

	/** Trails are kept in this file; null keeps them in memory only. */
	HollowsExits(Path file) {
		this(file, text -> { });
	}

	/** And a new way out is told in chat with this. */
	HollowsExits(Path file, java.util.function.Consumer<String> say) {
		this.file = file;
		this.say = say;
	}

	/** The player's position, every tick in the Hollows. Records a trail out of the Nucleus as it is walked. */
	void observe(double x, double y, double z) {
		boolean teleported = Math.sqrt(sq(x - lastX) + sq(y - lastY) + sq(z - lastZ)) > TELEPORT;
		double fromMiddle = Math.hypot(x - HollowsWalker.NUCLEUS_X, z - HollowsWalker.NUCLEUS_Z);
		if (Math.hypot(x - SPAWN_X, z - SPAWN_Z) < SPAWN_RADIUS) {
			current = new ArrayList<>();                  // at the spawn: the trail starts from here
			current.add(pack(x, y, z));
		} else if (current != null) {
			if (teleported || current.size() >= MAX_POINTS) {
				current = null;
			} else {
				long last = current.get(current.size() - 1);
				if (Math.sqrt(sq(x - px(last)) + sq(y - py(last)) + sq(z - pz(last))) >= STEP) {
					current.add(pack(x, y, z));
				}
				if (fromMiddle >= OUTSIDE) {
					finish(current, x, z);
					current = null;
				}
			}
		}
		lastX = x;
		lastY = y;
		lastZ = z;
	}

	private void finish(List<Long> trail, double x, double z) {
		load();
		int sector = HollowsWalker.sectorOf(Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16));
		List<Long> old = trails.get(sector);
		if (old == null || trail.size() < old.size()) {
			trails.put(sector, List.copyOf(trail));
			save();
			LOGGER.info("hollows walk: learned a way out of the Nucleus to the {} ({} points)",
					HollowsWalker.SECTOR_NAMES[sector], trail.size());
			say.accept("learned a way out of the Nucleus to the " + HollowsWalker.SECTOR_NAMES[sector] + " ("
					+ trails.size() + " known).");
		}
	}

	/**
	 * The way out that heads closest to this direction (0 west, 2 north, 4 east, 6 south), as points {@link #pack}ed; null when no
	 * way out has been learned.
	 */
	List<Long> toward(int sector) {
		load();
		List<Long> best = null;
		int bestTurn = Integer.MAX_VALUE;
		for (Map.Entry<Integer, List<Long>> e : trails.entrySet()) {
			int turn = Math.floorMod(e.getKey() - sector, HollowsWalker.SECTORS);
			turn = Math.min(turn, HollowsWalker.SECTORS - turn);
			if (turn < bestTurn) {
				bestTurn = turn;
				best = e.getValue();
			}
		}
		return best;
	}

	/** How many ways out are known. */
	int count() {
		load();
		return trails.size();
	}

	// Points are packed like HollowsPath positions, in tenths of a block, so a trail keeps where you really walked.

	static long pack(double x, double y, double z) {
		return HollowsPath.pack((int) Math.round(x * 10), (int) Math.round(y * 10), (int) Math.round(z * 10));
	}

	static double px(long p) {
		return HollowsPath.x(p) / 10.0;
	}

	static double py(long p) {
		return HollowsPath.y(p) / 10.0;
	}

	static double pz(long p) {
		return HollowsPath.z(p) / 10.0;
	}

	private static double sq(double v) {
		return v * v;
	}

	// One line per way out: the direction, then the points as x,y,z in blocks.

	private void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (file == null || !Files.exists(file)) {
			return;
		}
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String[] parts = line.trim().split(";");
				if (parts.length < 3) {
					continue;
				}
				List<Long> trail = new ArrayList<>();
				for (int i = 1; i < parts.length; i++) {
					String[] xyz = parts[i].split(",");
					trail.add(pack(Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2])));
				}
				long first = trail.get(0);
				if (Math.hypot(px(first) - SPAWN_X, pz(first) - SPAWN_Z) <= SPAWN_RADIUS + 2) {
					trails.put(Integer.parseInt(parts[0]), trail);   // older trails, started further out, are walked again
				}
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("hollows walk: could not read the ways out of the Nucleus from {}", file, e);
		}
	}

	private void save() {
		if (file == null) {
			return;
		}
		StringBuilder out = new StringBuilder();
		for (Map.Entry<Integer, List<Long>> e : trails.entrySet()) {
			out.append(e.getKey());
			for (long p : e.getValue()) {
				out.append(';').append(px(p)).append(',').append(py(p)).append(',').append(pz(p));
			}
			out.append('\n');
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, out, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("hollows walk: could not save the ways out of the Nucleus to {}", file, e);
		}
	}
}
