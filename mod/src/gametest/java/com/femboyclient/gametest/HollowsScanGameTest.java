package com.femboyclient.gametest;

import com.femboyclient.MinecraftChunks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.Map;
import java.util.Set;

/**
 * Checks the chunk reader against the real game: jasper placed in the world is found by the quick probe, the box around it is right,
 * a chunk without jasper is not reported, and one look at a chunk takes microseconds. Development only.
 */
public final class HollowsScanGameTest implements FabricClientGameTest {

	private static final Set<String> JASPER = Set.of("magenta_stained_glass", "magenta_stained_glass_pane");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			// Two blocks of jasper in chunk (0, 0): one glass, one pane, seven blocks apart, in different sections of the chunk.
			world.getServer().runCommand("setblock 3 -60 4 minecraft:magenta_stained_glass");
			world.getServer().runCommand("setblock 10 -30 9 minecraft:magenta_stained_glass_pane");
			context.waitTicks(20);

			context.runOnClient(client -> {
				MinecraftChunks chunks = new MinecraftChunks(client);
				boolean plain = chunks.hasJasper(5, 5);
				boolean withJasper = chunks.hasJasper(0, 0);
				Map<String, Integer> counts = chunks.blocks(0, 0);
				int[] box = chunks.locate(0, 0, JASPER);
				System.out.println("GAMETEST_SCAN chunk with jasper: probe=" + withJasper + " plain chunk probe=" + plain
						+ " kinds=" + counts.size() + " box=" + java.util.Arrays.toString(box));
				if (plain || !withJasper || box == null || box[0] != 3 || box[1] != -60 || box[2] != 4
						|| box[3] != 10 || box[4] != -30 || box[5] != 9
						|| counts.getOrDefault("magenta_stained_glass", 0) != 1 || counts.getOrDefault("magenta_stained_glass_pane", 0) != 1) {
					throw new AssertionError("the chunk reader is wrong: probe " + withJasper + ", plain " + plain + ", box " + java.util.Arrays.toString(box));
				}

				// Speed: the quick probe, the full count and the block search, over a real loaded chunk, after a warm-up.
				for (int i = 0; i < 2000; i++) {
					chunks.hasJasper(0, 0);
					chunks.blocks(0, 0);
					chunks.locate(0, 0, JASPER);
				}
				int rounds = 5000;
				long t0 = System.nanoTime();
				for (int i = 0; i < rounds; i++) {
					chunks.hasJasper(5, 5);
				}
				long t1 = System.nanoTime();
				for (int i = 0; i < rounds; i++) {
					chunks.blocks(0, 0);
				}
				long t2 = System.nanoTime();
				for (int i = 0; i < 500; i++) {
					chunks.locate(0, 0, JASPER);
				}
				long t3 = System.nanoTime();
				System.out.printf("GAMETEST_SCAN per chunk: probe %.1f us, full block count %.1f us, locate %.1f us%n",
						(t1 - t0) / 1000.0 / rounds, (t2 - t1) / 1000.0 / rounds, (t3 - t2) / 1000.0 / 500);
			});
		}
	}
}
