package com.femboyclient.gametest;

import com.femboyclient.McCompat;
import com.femboyclient.ClientMenuScreen;
import com.femboyclient.FemboyClientConfig;
import com.femboyclient.PlayerInfoScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Opens the G menu, selects "Auto Conversation", checks both its switches (in the list and in the settings pane) actually
 * change the setting, then opens the lobby list from "Lobby Networth" and checks Esc comes back to the menu with the same
 * module selected. Real screens, real clicks, no faking.
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
				expect(menu.selected() == 2, "clicking a row selects its module");
			});
			context.waitTicks(6);
			context.takeScreenshot("npcauto-module-selected");

			context.runOnClient(client -> {
				ClientMenuScreen menu = (ClientMenuScreen) McCompat.screen(client);
				boolean before = FemboyClientConfig.get().npcAuto;
				menu.mouseClicked(click(menu.paneSwitchBounds()), false);
				expect(FemboyClientConfig.get().npcAuto == !before, "the pane's switch flips the setting");
				menu.mouseClicked(click(menu.switchBounds(2)), false);
				expect(FemboyClientConfig.get().npcAuto == before, "and the list's switch flips it back");

				menu.mouseClicked(click(menu.rowBounds(3)), false);          // "Lobby Networth" is the fourth row
			});
			context.waitTicks(6);
			context.takeScreenshot("npcauto-lobby-networth-pane");

			context.runOnClient(client -> {
				ClientMenuScreen menu = (ClientMenuScreen) McCompat.screen(client);
				int[] open = menu.controlBounds("button:Open lobby list");
				expect(open != null, "Lobby Networth has an Open lobby list button");
				menu.mouseClicked(click(open), false);
			});
			context.waitTicks(6);
			context.runOnClient(client -> expect(McCompat.screen(client) instanceof PlayerInfoScreen, "the button opens the lobby list"));
			context.takeScreenshot("npcauto-lobby-list");

			context.runOnClient(client -> McCompat.screen(client).onClose());              // what Esc does
			context.waitTicks(6);
			context.runOnClient(client -> {
				Screen screen = McCompat.screen(client);
				expect(screen instanceof ClientMenuScreen, "Esc on the lobby list goes back to the G menu");
				expect(((ClientMenuScreen) screen).selected() == 3, "with Lobby Networth still selected");
			});
			context.takeScreenshot("npcauto-back-to-menu");
		}
	}
}
