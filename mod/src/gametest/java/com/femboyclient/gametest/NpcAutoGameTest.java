package com.femboyclient.gametest;

import com.femboyclient.McCompat;
import com.femboyclient.ClientMenuScreen;
import com.femboyclient.FemboyClientConfig;
import com.femboyclient.NpcAutoScreen;
import com.femboyclient.PlayerInfoScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Opens the G menu, clicks into "Auto Conversation", checks its ON/OFF switch actually changes the setting, clicks its
 * "◂ Menu" tab back to the hub, then into "Lobby Networth". Real screens, real clicks, no faking.
 * Development only: {@code gradlew runClientGametest}.
 */
public final class NpcAutoGameTest implements FabricClientGameTest {

	private static MouseButtonEvent click(int[] bounds) {
		double x = (bounds[0] + bounds[2]) / 2.0, y = (bounds[1] + bounds[3]) / 2.0;
		return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
	}

	private static void expect(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
		System.out.println("GAMETEST_NPCAUTO ok: " + what);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			FemboyClientConfig.get().npcAuto = true;

			context.runOnClient(client -> client.setScreenAndShow(new ClientMenuScreen()));
			context.waitTicks(6);
			context.takeScreenshot("npcauto-menu");

			context.runOnClient(client -> {
				Screen screen = McCompat.screen(client);
				expect(screen instanceof ClientMenuScreen, "the G menu opened");
				ClientMenuScreen menu = (ClientMenuScreen) screen;
				menu.mouseClicked(click(menu.rowBounds(2)), false);          // "Auto Conversation" is the third row
			});
			context.waitTicks(6);
			context.takeScreenshot("npcauto-module-open");

			context.runOnClient(client -> {
				Screen screen = McCompat.screen(client);
				expect(screen instanceof NpcAutoScreen, "the menu opened Auto Conversation");
				NpcAutoScreen auto = (NpcAutoScreen) screen;

				boolean before = FemboyClientConfig.get().npcAuto;
				auto.mouseClicked(click(auto.switchBounds()), false);
				expect(FemboyClientConfig.get().npcAuto == !before, "the ON/OFF switch actually flips the setting");
				auto.mouseClicked(click(auto.switchBounds()), false);
				expect(FemboyClientConfig.get().npcAuto == before, "and flips back");

				auto.mouseClicked(click(auto.menuTabBounds()), false);
			});
			context.waitTicks(6);
			context.runOnClient(client -> expect(McCompat.screen(client) instanceof ClientMenuScreen, "the Menu tab goes back to the hub"));
			context.takeScreenshot("npcauto-back-to-menu");

			context.runOnClient(client -> {
				ClientMenuScreen menu = (ClientMenuScreen) McCompat.screen(client);
				menu.mouseClicked(click(menu.rowBounds(3)), false);          // "Lobby Networth" is the fourth row
			});
			context.waitTicks(6);
			context.runOnClient(client -> expect(McCompat.screen(client) instanceof PlayerInfoScreen, "the hub also opens Lobby Networth"));
			context.takeScreenshot("npcauto-lobby-networth");
		}
	}
}
