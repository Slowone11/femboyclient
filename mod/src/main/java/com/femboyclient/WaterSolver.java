package com.femboyclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Water Board solver (a copy of OdinClient 1.2.5.beta7's): reads which blocks sit by the piston and which wool gates are
 * open, looks the answer up in Odin's watertimes.json, and shows over each lever when to flip it ("CLICK ME!" or a countdown
 * after the water lever), with the order of the flips and a line to the next lever.
 */
final class WaterSolver {

	private enum LeverBlock {
		QUARTZ, GOLD, COAL, DIAMOND, EMERALD, CLAY, WATER, NONE;

		int clicked;

		BlockPos pos() {
			if (this == WATER) {
				int[] at = Dungeons.Room.offset(chestX, chestZ, facing, 17);
				return new BlockPos(at[0], 60, at[1]);
			}
			int shiftBy = ordinal() % 3 * 5;
			Dungeons.Rotation side = ordinal() < 3 ? facing.rotateY() : facing.rotateYCCW();
			int[] at = Dungeons.Room.offset(chestX, chestZ, side, 5);
			at = Dungeons.Room.offset(at[0], at[1], facing, shiftBy + 2);
			return new BlockPos(at[0], 61, at[1]);
		}
	}

	private static final JsonObject SOLUTIONS = load();

	private static int chestX, chestZ;
	private static Dungeons.Rotation facing = Dungeons.Rotation.NORTH;
	private static int variant = -1;
	private static final Map<LeverBlock, double[]> solutions = new EnumMap<>(LeverBlock.class);
	private static long openedWater = -1;

	private WaterSolver() {
	}

	private static JsonObject load() {
		try (var in = WaterSolver.class.getResourceAsStream("/femboyclient/odin/watertimes.json")) {
			return in == null ? new JsonObject() : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			FemboyClientMod.LOGGER.warn("could not read the water board solutions", e);
			return new JsonObject();
		}
	}

	static void scan(Minecraft mc) {
		Dungeons.Room room = Dungeons.room();
		if (room == null || !room.name().equals("Water Board") || room.rotation() == null || variant != -1) {
			return;
		}
		facing = room.rotation();
		int[] chest = Dungeons.Room.offset(room.x(), room.z(), facing, -7);
		chestX = chest[0];
		chestZ = chest[1];

		int[] piston = Dungeons.Room.offset(chestX, chestZ, facing, -5);
		boolean gold = false, clay = false, emerald = false, quartz = false, diamond = false;
		for (BlockPos pos : BlockPos.betweenClosed(piston[0] - 1, 77, piston[1] - 1, piston[0] + 1, 78, piston[1] + 1)) {
			Block block = mc.level.getBlockState(pos).getBlock();
			gold |= block == Blocks.GOLD_BLOCK;
			clay |= block == Blocks.TERRACOTTA;
			emerald |= block == Blocks.EMERALD_BLOCK;
			quartz |= block == Blocks.QUARTZ_BLOCK;
			diamond |= block == Blocks.DIAMOND_BLOCK;
		}
		int found = gold && clay ? 0 : emerald && quartz ? 1 : quartz && diamond ? 2 : gold && quartz ? 3 : -1;

		StringBuilder extended = new StringBuilder();
		for (int wool = 0; wool < 5; wool++) {
			int[] at = Dungeons.Room.offset(chestX, chestZ, facing, 3 + wool);
			if (mc.level.getBlockState(new BlockPos(at[0], 56, at[1])).is(BlockTags.WOOL)) {
				extended.append(wool);
			}
		}
		if (found == -1 || extended.length() != 3) {
			return;                // not loaded yet: try again
		}
		variant = found;
		PuzzleSolvers.message("Variant: " + variant + ":" + extended + ":" + facing.name());

		solutions.clear();
		JsonElement forVariant = SOLUTIONS.get(Integer.toString(variant));
		JsonElement forSlots = forVariant == null ? null : forVariant.getAsJsonObject().get(extended.toString());
		if (forSlots == null) {
			return;
		}
		for (Map.Entry<String, JsonElement> entry : forSlots.getAsJsonObject().entrySet()) {
			LeverBlock lever = switch (entry.getKey()) {
				case "minecraft:quartz_block" -> LeverBlock.QUARTZ;
				case "minecraft:gold_block" -> LeverBlock.GOLD;
				case "minecraft:coal_block" -> LeverBlock.COAL;
				case "minecraft:diamond_block" -> LeverBlock.DIAMOND;
				case "minecraft:emerald_block" -> LeverBlock.EMERALD;
				case "minecraft:hardened_clay" -> LeverBlock.CLAY;
				case "minecraft:water" -> LeverBlock.WATER;
				default -> LeverBlock.NONE;
			};
			var times = entry.getValue().getAsJsonArray();
			double[] values = new double[times.size()];
			for (int i = 0; i < values.length; i++) {
				values[i] = times.get(i).getAsDouble();
			}
			solutions.put(lever, values);
		}
	}

	static void render(Minecraft mc, FemboyClientConfig config) {
		if (!Dungeons.roomName().equals("Water Board") || solutions.isEmpty()) {
			return;
		}
		List<Object[]> solutionList = new ArrayList<>();          // {lever, time}
		List<Double> sorted = new ArrayList<>();
		for (var entry : solutions.entrySet()) {
			double[] times = entry.getValue();
			for (int i = entry.getKey().clicked; i < times.length; i++) {
				solutionList.add(new Object[] {entry.getKey(), times[i]});
				if (times[i] != 0) {
					sorted.add(times[i]);
				}
			}
		}
		solutionList.sort(Comparator.comparingDouble(o -> (double) o[1] + (o[0] == LeverBlock.WATER ? 0.01 : 0)));
		sorted.sort(null);
		if (solutionList.isEmpty()) {
			return;
		}

		LeverBlock first = (LeverBlock) solutionList.get(0)[0];
		if (config.puzzleWaterTracer) {
			WorldDraw.line(mc.player.position(), Vec3.atCenterOf(first.pos()), 0xFF00FF00, 2F, true);
			if (solutionList.size() > 1) {
				LeverBlock second = (LeverBlock) solutionList.get(1)[0];
				if (!first.pos().equals(second.pos())) {
					WorldDraw.line(Vec3.atCenterOf(first.pos()), Vec3.atCenterOf(second.pos()), 0xFFFFA500, 1.5F, true);
				}
			}
		}
		for (var entry : solutions.entrySet()) {
			LeverBlock lever = entry.getKey();
			double[] times = entry.getValue();
			if (config.puzzleWaterOrder) {
				StringBuilder order = new StringBuilder();
				for (int i = lever.clicked; i < times.length; i++) {
					if (times[i] == 0) {
						order.append("0");
					} else {
						order.append(order.isEmpty() ? "" : ", ").append(sorted.indexOf(times[i]) + 1);
					}
				}
				WorldDraw.text(order.toString(), Vec3.atCenterOf(lever.pos()), 0xFFFFFFFF, 0.035F, true);
			}
			for (int i = lever.clicked; i < times.length; i++) {
				String text;
				if (openedWater == -1) {
					text = times[i] == 0 ? "§a§lCLICK ME!" : "§e" + times[i] + "s";
				} else {
					double remaining = openedWater + times[i] * 1000 - System.currentTimeMillis();
					text = remaining > 0 ? "§e" + String.format(Locale.US, "%.2f", remaining / 1000) + "s" : "§a§lCLICK ME!";
				}
				Vec3 at = Vec3.atBottomCenterOf(lever.pos()).add(0, (i - lever.clicked) * 0.5 + 1.5, 0);
				WorldDraw.text(text, at, 0xFFFFFFFF, 0.04F, true);
			}
		}
	}

	/** You right clicked a block: if it is one of the levers, that flip is done. */
	static void onInteract(BlockPos pos) {
		if (solutions.isEmpty()) {
			return;
		}
		for (LeverBlock lever : LeverBlock.values()) {
			if (lever != LeverBlock.NONE && lever.pos().equals(pos)) {
				lever.clicked++;
				if (lever == LeverBlock.WATER && openedWater == -1) {
					openedWater = System.currentTimeMillis();
				}
				return;
			}
		}
	}

	static void reset() {
		variant = -1;
		solutions.clear();
		openedWater = -1;
		for (LeverBlock lever : LeverBlock.values()) {
			lever.clicked = 0;
		}
	}
}
