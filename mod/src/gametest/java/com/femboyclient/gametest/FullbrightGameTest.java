package com.femboyclient.gametest;

import com.femboyclient.FemboyClientConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Checks Fullbright against the real renderer: the player stands in a sealed stone box with no light at all, and the
 * wall in front must go from near black with Fullbright off to clearly lit with it on. Development only.
 */
public final class FullbrightGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		boolean wasOn = context.computeOnClient(client -> FemboyClientConfig.get().fullbright);
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			// A hollow stone box: no sky light reaches inside and nothing in it gives off block light.
			world.getServer().runCommand("fill -6 -61 -6 6 -50 6 minecraft:stone");
			world.getServer().runCommand("fill -5 -60 -5 5 -51 5 minecraft:air");
			world.getServer().runCommand("tp @p 0 -60 0 0 0");
			context.waitTicks(40);
			world.getConnection().waitForChunksRender();

			context.runOnClient(client -> FemboyClientConfig.get().fullbright = false);
			context.waitTicks(10);
			double off = brightness(context.takeScreenshot("fullbright-off"));

			context.runOnClient(client -> FemboyClientConfig.get().fullbright = true);
			context.waitTicks(10);
			double on = brightness(context.takeScreenshot("fullbright-on"));

			System.out.printf("GAMETEST_FULLBRIGHT mean brightness of the middle of the screen: off %.1f, on %.1f%n", off, on);
			if (on < off + 60) {
				throw new AssertionError("Fullbright did not light the dark box: off " + off + ", on " + on);
			}
		} finally {
			context.runOnClient(client -> FemboyClientConfig.get().fullbright = wasOn);
		}
	}

	/** Mean of red, green and blue (0 to 255) over the middle half of the screenshot, which avoids the hotbar. */
	private static double brightness(Path screenshot) {
		BufferedImage image;
		try {
			image = ImageIO.read(screenshot.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		int w = image.getWidth();
		int h = image.getHeight();
		long sum = 0;
		long count = 0;
		for (int y = h / 4; y < h * 3 / 4; y++) {
			for (int x = w / 4; x < w * 3 / 4; x++) {
				int rgb = image.getRGB(x, y);
				sum += ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
				count += 3;
			}
		}
		return (double) sum / count;
	}
}
