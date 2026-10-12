package com.femboyclient.gametest;

import com.femboyclient.FemboyClientConfig;
import com.femboyclient.LoopedClickerAccess;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

/**
 * Checks the Looped Clicker panel still shows when its saved spot is past the screen's edge, as happens after
 * dragging it at a smaller GUI scale. Screenshot only; development only.
 */
public final class LoopedClickerGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		int[] saved = context.computeOnClient(client -> {
			FemboyClientConfig c = FemboyClientConfig.get();
			return new int[] {c.clicker ? 1 : 0, c.clickerPanelX, c.clickerPanelY, c.clickerMs, c.clickerX, c.clickerY};
		});
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			context.runOnClient(client -> {
				FemboyClientConfig c = FemboyClientConfig.get();
				c.clicker = true;
				c.clickerPanelX = 5000;
				c.clickerPanelY = 5000;
				client.setScreenAndShow(new InventoryScreen(client.player));
			});
			context.waitForScreen(InventoryScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("loopedclicker-offscreen-spot");

			// Speed: at 10 ms it must really click about 100 times a second, not once per 50 ms tick.
			context.runOnClient(client -> {
				FemboyClientConfig c = FemboyClientConfig.get();
				c.clickerMs = 10;
				c.clickerX = 5;      // empty space outside the inventory: clicking there does nothing
				c.clickerY = 5;
			});
			long t0 = System.nanoTime();
			int before = context.computeOnClient(client -> {
				LoopedClickerAccess.start();
				return LoopedClickerAccess.clicks();
			});
			context.waitTicks(40);
			int after = context.computeOnClient(client -> LoopedClickerAccess.clicks());
			double seconds = (System.nanoTime() - t0) / 1e9;
			double rate = (after - before) / seconds;
			System.out.printf("GAMETEST_CLICKER %d clicks in %.2f s = %.0f per second%n", after - before, seconds, rate);
			if (rate < 70) {
				throw new AssertionError("Looped Clicker at 10 ms only clicked " + rate + " times a second");
			}

			// Closing the container pauses the loop instead of stopping it.
			context.runOnClient(client -> client.setScreenAndShow(null));
			context.waitTicks(60);
			boolean stillRunning = context.computeOnClient(client -> LoopedClickerAccess.running());
			context.runOnClient(client -> LoopedClickerAccess.stop());
			if (!stillRunning) {
				throw new AssertionError("Looped Clicker stopped when the container closed");
			}
		} finally {
			context.runOnClient(client -> {
				FemboyClientConfig c = FemboyClientConfig.get();
				c.clicker = saved[0] == 1;
				c.clickerPanelX = saved[1];
				c.clickerPanelY = saved[2];
				c.clickerMs = saved[3];
				c.clickerX = saved[4];
				c.clickerY = saved[5];
				LoopedClickerAccess.stop();
			});
		}
	}
}
