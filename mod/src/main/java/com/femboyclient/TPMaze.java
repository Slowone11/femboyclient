package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Teleport Maze solver (a copy of OdinClient 1.2.5.beta7's): every time a pad teleports you, the pads you could be facing
 * toward (within 60 blocks of where it points you) are kept, the rest are dropped, so the exit pad is narrowed down. One pad
 * left is green, several are orange, and the pads you already used are red.
 */
public final class TPMaze {

	private static final int ONE = 0x7F00FF00, MANY = 0x7FFFA500, VISITED = 0x7FFF0000;
	private static final double FRAME_HEIGHT = 13 / 16.0;

	private static final Set<BlockPos> portals = new HashSet<>();
	private static List<BlockPos> correct = new ArrayList<>();
	private static final Set<BlockPos> visited = new HashSet<>();
	private static Vec3 lastPosition;

	private TPMaze() {
	}

	private static boolean inMaze() {
		return Dungeons.roomName().equals("Teleport Maze");
	}

	static void scan(Minecraft mc) {
		if (portals.size() >= 30 || !inMaze()) {
			return;
		}
		BlockPos p = mc.player.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(p.getX() - 22, 69, p.getZ() - 22, p.getX() + 22, 70, p.getZ() + 22)) {
			if (mc.level.getBlockState(pos).getBlock() == Blocks.END_PORTAL_FRAME) {
				portals.add(pos.immutable());
			}
		}
	}

	/** Called every client tick: remembers where you stood, which is where you were just before a teleport. */
	static void tick(Minecraft mc, boolean on) {
		lastPosition = mc.player == null || !on ? null : mc.player.position();
	}

	/** The server just put you somewhere else (called after the game has moved you). */
	public static void onTeleport() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || lastPosition == null || !PuzzleSolvers.on() || !FemboyClientConfig.get().puzzleTpMaze || !inMaze()) {
			return;
		}
		Vec3 now = mc.player.position(), before = lastPosition;
		AABB to = blockBox(BlockPos.containing(now)).inflate(0.5, 0, 0.5), from = blockBox(BlockPos.containing(before)).inflate(0.5, 0, 0.5);
		for (BlockPos portal : portals) {
			if (to.intersects(blockBox(portal)) || from.intersects(blockBox(portal))) {
				visited.add(portal);
			}
		}
		if (correct.isEmpty()) {
			correct = new ArrayList<>(portals);
		}
		float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
		correct.removeIf(it -> !xzInterceptable(new AABB(it.getX(), it.getY(), it.getZ(), it.getX() + 1, it.getY() + 4, it.getZ() + 1)
				.inflate(0.75, 0, 0.75), 60, now, yaw, pitch) || blockBox(it).inflate(0.5, 0, 0.5).contains(before));
	}

	private static AABB blockBox(BlockPos pos) {
		return new AABB(pos);
	}

	/** Odin's isXZInterceptable: does the line you look along (from your eyes, {@code range} long) pass the box's sides. */
	private static boolean xzInterceptable(AABB box, float range, Vec3 pos, float yaw, float pitch) {
		Vec3 start = pos.add(0, 1.62, 0);
		Vec3 goal = start.add(Vec3.directionFromRotation(pitch, yaw).scale(range));
		return inZ(atX(start, goal, box.minX), box) || inZ(atX(start, goal, box.maxX), box)
				|| inX(atZ(start, goal, box.minZ), box) || inX(atZ(start, goal, box.maxZ), box);
	}

	private static Vec3 atX(Vec3 a, Vec3 b, double x) {
		double dx = b.x - a.x;
		if (dx * dx < 1e-7) {
			return null;
		}
		double t = (x - a.x) / dx;
		return t < 0 || t > 1 ? null : a.add((b.x - a.x) * t, (b.y - a.y) * t, (b.z - a.z) * t);
	}

	private static Vec3 atZ(Vec3 a, Vec3 b, double z) {
		double dz = b.z - a.z;
		if (dz * dz < 1e-7) {
			return null;
		}
		double t = (z - a.z) / dz;
		return t < 0 || t > 1 ? null : a.add((b.x - a.x) * t, (b.y - a.y) * t, (b.z - a.z) * t);
	}

	private static boolean inZ(Vec3 v, AABB box) {
		return v != null && v.z >= box.minZ && v.z <= box.maxZ;
	}

	private static boolean inX(Vec3 v, AABB box) {
		return v != null && v.x >= box.minX && v.x <= box.maxX;
	}

	static void render(FemboyClientConfig config) {
		if (!inMaze()) {
			return;
		}
		int color = correct.size() == 1 ? ONE : MANY;
		for (BlockPos pos : correct) {
			if (visited.contains(pos) && correct.size() != 1) {
				continue;
			}
			WorldDraw.box(frame(pos), color, true, false, 1F, !(config.puzzleTpMazeThroughWalls && correct.size() == 1));
		}
		for (BlockPos pos : visited) {
			WorldDraw.box(frame(pos), VISITED, true, false, 1F, true);
		}
	}

	private static AABB frame(BlockPos pos) {
		return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + FRAME_HEIGHT, pos.getZ() + 1).inflate(0.005);
	}

	static void reset() {
		portals.clear();
		correct = new ArrayList<>();
		visited.clear();
	}
}
