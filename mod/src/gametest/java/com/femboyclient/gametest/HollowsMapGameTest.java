package com.femboyclient.gametest;

import com.femboyclient.McCompat;
import com.femboyclient.FemboyClientConfig;
import com.femboyclient.HollowsMapScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Opens the place-the-map screen in a real (test) game and takes screenshots, then drags and scrolls it and checks the placement
 * changed and stays on screen. Development only: {@code gradlew runClientGameTest}.
 */
public final class HollowsMapGameTest implements FabricClientGameTest {

	private static MouseButtonEvent click(double x, double y) {
		return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			FemboyClientConfig config = FemboyClientConfig.get();
			config.hudX = 4;
			config.hudY = 4;
			config.hudSize = 150;

			for (int scale : new int[] {2, 3, 4}) {
				context.runOnClient(client -> {
					client.options.guiScale().set(scale);
					client.resizeGui();
				});
				context.waitTicks(4);
				context.setScreen(HollowsMapScreen::new);
				context.waitTicks(10);
				context.takeScreenshot("hollowsmap-scale-" + scale);
			}

			context.runOnClient(client -> {
				client.options.guiScale().set(2);
				client.resizeGui();
			});
			context.waitTicks(4);
			context.setScreen(HollowsMapScreen::new);
			context.waitTicks(6);

			// Drag it by its top edge to the middle of the screen, then scroll to make it bigger.
			context.runOnClient(client -> {
				Screen screen = McCompat.screen(client);
				screen.mouseClicked(click(config.hudX + 20, config.hudY + 3), false);
				screen.mouseDragged(click(config.hudX + 200, config.hudY + 100), 180, 97);
				screen.mouseReleased(click(config.hudX + 200, config.hudY + 100));
				screen.mouseScrolled(100, 100, 0, 1);
				screen.mouseScrolled(100, 100, 0, 1);
			});
			context.waitTicks(6);
			context.takeScreenshot("hollowsmap-moved-and-bigger");
			// Take the bottom right corner and pull it: the map grows and its top left corner stays.
			int[] edge = HollowsMapScreen.corner();
			int[] corner = {edge[0] - 4, edge[1] - 4};
			int before = config.hudSize, left = config.hudX, top = config.hudY;
			context.runOnClient(client -> {
				Screen screen = McCompat.screen(client);
				screen.mouseClicked(click(corner[0], corner[1]), false);
				screen.mouseDragged(click(corner[0] + 40, corner[1] + 40), 40, 40);
				screen.mouseReleased(click(corner[0] + 40, corner[1] + 40));
			});
			context.waitTicks(6);
			context.takeScreenshot("hollowsmap-corner-resized");
			System.out.println("GAMETEST_HUD corner: size " + before + " -> " + config.hudSize);
			if (config.hudSize != before + 40 || config.hudX != left || config.hudY != top) {
				throw new AssertionError("the corner did not resize the map: " + before + " -> " + config.hudSize);
			}
			System.out.println("GAMETEST_HUD placed at " + config.hudX + "," + config.hudY + " size " + config.hudSize);
			if (config.hudX <= 100 || config.hudY <= 60 || config.hudSize != 210) {
				throw new AssertionError("the map did not move or resize: " + config.hudX + "," + config.hudY + " size " + config.hudSize);
			}
		}
	}
}
