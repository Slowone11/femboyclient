package com.femboyclient.gametest;

import com.femboyclient.McCompat;
import com.femboyclient.LiveInput;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs input from the Live tab in a real (test) game and checks it does what it should: the hotbar slot changes, a click with no
 * menu is a game click, and with a menu open the click, wheel, keys and typing reach the menu at the right spot.
 * Development only: {@code gradlew runClientGametest}.
 */
public final class LiveInputGameTest implements FabricClientGameTest {

	/** A menu that writes down everything it is given. */
	private static final class Probe extends Screen {
		final List<String> seen = new ArrayList<>();

		Probe() {
			super(Component.literal("probe"));
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			seen.add("click " + Math.round(event.x()) + "," + Math.round(event.y()) + " b" + event.button() + " m" + event.modifiers());
			return true;
		}

		@Override
		public boolean mouseReleased(MouseButtonEvent event) {
			seen.add("release b" + event.button());
			return true;
		}

		@Override
		public boolean mouseScrolled(double x, double y, double dx, double dy) {
			seen.add("scroll " + Math.round(x) + "," + Math.round(y) + " " + dy);
			return true;
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			seen.add("key " + event.key());
			return true;
		}

		@Override
		public boolean charTyped(CharacterEvent event) {
			seen.add("char " + (char) event.codepoint());
			return true;
		}
	}

	private static void expect(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
		System.out.println("GAMETEST_INPUT ok: " + what);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);

			// Hotbar slot 3 (the 3rd item), then the wheel with no menu open moves along the hotbar.
			context.runOnClient(client -> {
				LiveInput.applyJson(client, "{\"events\":[{\"type\":\"slot\",\"n\":3}]}");
				expect(client.player.getInventory().getSelectedSlot() == 2, "slot 3 selects the 3rd hotbar slot");
				LiveInput.applyJson(client, "{\"events\":[{\"type\":\"scroll\",\"x\":0.5,\"y\":0.5,\"dy\":-1}]}");
				expect(client.player.getInventory().getSelectedSlot() == 3, "wheel down moves to the next slot");
			});

			// With no menu open a right click is the game's own use click, and a left click its attack.
			context.runOnClient(client -> {
				expect(McCompat.screen(client) == null, "no menu is open");
				while (client.options.keyUse.consumeClick()) { /* clear */ }
				LiveInput.applyJson(client, "{\"events\":[{\"type\":\"click\",\"x\":0.5,\"y\":0.5,\"button\":1}]}");
				expect(client.options.keyUse.consumeClick(), "right click with no menu presses the use key once");
				expect(!client.options.keyUse.consumeClick(), "...and only once");
				while (client.options.keyAttack.consumeClick()) { /* clear */ }
				LiveInput.applyJson(client, "{\"events\":[{\"type\":\"click\",\"x\":0.1,\"y\":0.1,\"button\":0}]}");
				expect(client.options.keyAttack.consumeClick(), "left click with no menu presses the attack key");
			});

			// With a menu open: clicks land at the spot, with their button and shift; wheel, keys and text reach it.
			Probe[] made = new Probe[1];
			int[] gui = new int[2];
			context.runOnClient(client -> {
				gui[0] = client.getWindow().getGuiScaledWidth();
				gui[1] = client.getWindow().getGuiScaledHeight();
				made[0] = new Probe();
				client.setScreenAndShow(made[0]);
			});
			context.waitTicks(4);
			context.runOnClient(client -> {
				LiveInput.applyJson(client, "{\"events\":["
						+ "{\"type\":\"click\",\"x\":0.5,\"y\":0.25,\"button\":1,\"shift\":true},"
						+ "{\"type\":\"scroll\",\"x\":0.5,\"y\":0.5,\"dy\":1},"
						+ "{\"type\":\"key\",\"key\":\"enter\"},"
						+ "{\"type\":\"text\",\"text\":\"ab\"}]}");
			});
			Probe probe = made[0];
			System.out.println("GAMETEST_INPUT probe saw " + probe.seen + " (gui " + gui[0] + "x" + gui[1] + ")");
			expect(probe.seen.contains("click " + Math.round(gui[0] * 0.5) + "," + Math.round(gui[1] * 0.25) + " b1 m1"),
					"click lands at the clicked spot with its button and shift");
			expect(probe.seen.contains("release b1"), "the click is released");
			expect(probe.seen.contains("scroll " + Math.round(gui[0] * 0.5) + "," + Math.round(gui[1] * 0.5) + " 1.0"), "wheel reaches the menu");
			expect(probe.seen.contains("key 257"), "Enter reaches the menu");
			expect(probe.seen.contains("char a") && probe.seen.contains("char b"), "typed text reaches the menu");

			// Esc closes a real menu.
			context.runOnClient(client -> client.setScreenAndShow(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player)));
			context.waitTicks(4);
			context.runOnClient(client -> {
				expect(McCompat.screen(client) != null, "the inventory is open");
				LiveInput.applyJson(client, "{\"events\":[{\"type\":\"key\",\"key\":\"escape\"}]}");
			});
			context.waitTicks(4);
			context.runOnClient(client -> expect(McCompat.screen(client) == null, "Esc closes the inventory"));
		}
	}
}
