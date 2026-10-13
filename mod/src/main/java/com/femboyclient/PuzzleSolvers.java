package com.femboyclient;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionResult;

/**
 * Puzzle Solvers (a copy of OdinClient 1.2.5.beta7's): Water Board, Teleport Maze, Tic Tac Toe, Ice Fill and Blaze, each with its
 * own switch in the G menu. They work in the dungeon room of that name, which {@link Dungeons} recognises; every solver starts
 * over in a new world, and each has a Reset button.
 */
public final class PuzzleSolvers {

	public static final HandMan.Range BLAZE_LINES = new HandMan.Range(1, 10, 1);
	public static final String[] BLAZE_STYLES = {"Filled", "Outline", "Filled Outline"};

	private static ClientLevel level;
	private static int ticks;

	private PuzzleSolvers() {
	}

	static boolean on() {
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && config.puzzleSolvers;
	}

	public static void register() {
		Dungeons.onRoomEnter(room -> {
			IceFillSolver.onRoomEnter(room);
			BlazeSolver.onRoomEnter(room);
		});
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClientSide() && on() && FemboyClientConfig.get().puzzleWater) {
				WaterSolver.onInteract(hit.getBlockPos());
			}
			return InteractionResult.PASS;
		});
	}

	/** Called every client tick. */
	public static void tick(Minecraft mc) {
		if (mc.level != level) {
			level = mc.level;
			WaterSolver.reset();
			TPMaze.reset();
			TicTacToe.reset();
			IceFillSolver.reset();
			BlazeSolver.reset();
		}
		if (mc.level == null || mc.player == null || !on() || !Dungeons.inDungeons()) {
			TPMaze.tick(mc, false);
			return;
		}
		FemboyClientConfig config = FemboyClientConfig.get();
		if (ticks++ % 10 == 0) {
			if (config.puzzleTpMaze) {
				TPMaze.scan(mc);
			}
			if (config.puzzleWater) {
				WaterSolver.scan(mc);
			}
		}
		TPMaze.tick(mc, config.puzzleTpMaze);
		if (config.puzzleTtt) {
			TicTacToe.tick(mc);
		}
		if (config.puzzleBlaze) {
			BlazeSolver.tick(mc);
		}

		if (config.puzzleWater) {
			WaterSolver.render(mc, config);
		}
		if (config.puzzleTpMaze) {
			TPMaze.render(config);
		}
		if (config.puzzleTtt) {
			TicTacToe.render();
		}
		if (config.puzzleIceFill) {
			IceFillSolver.render();
		}
		if (config.puzzleBlaze) {
			BlazeSolver.render(config);
		}
	}

	/** Odin's modMessage. */
	static void message(String text) {
		Chat.showLater(net.minecraft.network.chat.Component.literal("§3Femboy Client §8»§r " + text));
	}
}
