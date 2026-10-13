package com.femboyclient;

import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Ice Fill solver (a copy of OdinClient 1.2.5.beta7's): when you walk into the Ice Fill room, each of its three floors is
 * matched against Odin's known layouts (icefillFloors.json) by a pair of blocks, and the path that fills the floor is drawn on
 * it as one line, pink, from the start of the first floor to the stairs of the last.
 */
final class IceFillSolver {

	/** For each floor, per layout: {x, z} that must be air and {x, z} that must not be, from the floor's start. */
	private static final int[][][] FLOORS = load("/femboyclient/odin/icefillFloors.json", int[][][].class);
	/** For each floor, per layout: the path, as {x, y, z} steps from the floor's start. */
	private static final int[][][][] PATTERNS = load("/femboyclient/odin/icefillPatterns.json", int[][][][].class);
	private static final int COLOR = 0xFFFF55FF;

	private static boolean scanned;
	private static final List<int[][]> patterns = new ArrayList<>();
	private static final List<Vec3> starts = new ArrayList<>();
	private static Dungeons.Rotation rotation;

	private IceFillSolver() {
	}

	private static <T> T load(String path, Class<T> type) {
		try (var in = IceFillSolver.class.getResourceAsStream(path)) {
			return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
		} catch (Exception e) {
			FemboyClientMod.LOGGER.warn("could not read {}", path, e);
			return type.cast(java.lang.reflect.Array.newInstance(type.getComponentType(), 0));
		}
	}

	static void onRoomEnter(Dungeons.Room room) {
		FemboyClientConfig config = FemboyClientConfig.get();
		if (room == null || !room.name().equals("Ice Fill") || room.rotation() == null || scanned || !PuzzleSolvers.on()
				|| !config.puzzleIceFill) {
			return;
		}
		rotation = room.rotation();
		int[] start = Dungeons.Room.offset(room.x(), room.z(), rotation, 8);
		Vec3 pos = new Vec3(start[0], 70, start[1]);
		scan(pos, 0);
		scan(pos.add(transformTo(5, 1, 0)), 1);
		scan(pos.add(transformTo(12, 2, 0)), 2);
		scanned = true;
	}

	private static void scan(Vec3 pos, int floor) {
		if (floor >= FLOORS.length) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		BlockPos at = BlockPos.containing(pos);
		long startTime = System.nanoTime();
		int[][] layouts = FLOORS[floor];
		for (int index = 0; index < layouts.length; index++) {
			int[] check = layouts[index];
			if (air(mc, at, check[0], check[1]) && !air(mc, at, check[2], check[3])) {
				PuzzleSolvers.message("Section " + (floor + 1) + " scan took " + (System.nanoTime() - startTime) / 1_000_000.0
						+ "ms pattern: " + (index + 1));
				starts.add(new Vec3(pos.x + 0.5, pos.y + 0.1, pos.z + 0.5));
				patterns.add(PATTERNS[floor][index]);
				return;
			}
		}
		PuzzleSolvers.message("§cFailed to scan floor " + (floor + 1));
	}

	private static boolean air(Minecraft mc, BlockPos at, int x, int z) {
		int[] d = transform(x, z);
		return mc.level.getBlockState(at.offset(d[0], 0, d[1])).isAir();
	}

	private static int[] transform(int x, int z) {
		return switch (rotation) {
			case NORTH -> new int[] {z, -x};
			case WEST -> new int[] {-x, -z};
			case SOUTH -> new int[] {-z, x};
			case EAST -> new int[] {x, z};
		};
	}

	private static Vec3 transformTo(int x, int y, int z) {
		return switch (rotation) {
			case NORTH -> new Vec3(z, y, -x);
			case WEST -> new Vec3(-x, y, -z);
			case SOUTH -> new Vec3(-z, y, x);
			case EAST -> new Vec3(x, y, z);
		};
	}

	static void render() {
		if (patterns.isEmpty() || !Dungeons.roomName().equals("Ice Fill") || rotation == null) {
			return;
		}
		Vec3 previous = null;
		for (int i = 0; i < patterns.size(); i++) {
			int[][] pattern = patterns.get(i);
			Vec3 start = starts.get(i);
			List<Vec3> points = new ArrayList<>();
			points.add(start);
			for (int[] p : pattern) {
				points.add(start.add(transformTo(p[0], p[1], p[2])));
			}
			int[] last = pattern[pattern.length - 1];
			points.add(start.add(transformTo(last[0] + 1, last[1] + 1, last[2])));
			for (Vec3 point : points) {
				if (previous != null) {
					WorldDraw.line(previous, point, COLOR, 3F, true);
				}
				previous = point;
			}
		}
	}

	static void reset() {
		patterns.clear();
		starts.clear();
		scanned = false;
		rotation = null;
	}
}
