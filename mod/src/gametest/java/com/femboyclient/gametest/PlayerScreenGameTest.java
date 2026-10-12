package com.femboyclient.gametest;

import com.femboyclient.McCompat;
import com.femboyclient.LobbyPlayer;
import com.femboyclient.PlayerInfo;
import com.femboyclient.PlayerInfoScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Opens the player screen in a real (test) game with made-up players and answers, takes screenshots at several GUI scales and
 * checks the click-to-copy. Development only: run it with {@code gradlew runClientGameTest}. Screenshots are written to
 * {@code build/run/clientGameTest/screenshots}.
 */
public final class PlayerScreenGameTest implements FabricClientGameTest {

	private static final String[] NAMES = {"WeTrollALittle", "Technoblade", "Dream", "BayHarborBeater", "Alice", "Bob_the_Builder", "xX_Slayer_Xx",
			"Notch", "jeb_", "Dinnerbone", "Grumm", "Steve", "Alex_2000", "Hypixel"};

	/** The player of this test world: the one player who really is in the tab list, so the face and the 3D model can be shown. */
	private static LobbyPlayer me;

	private static List<LobbyPlayer> players() {
		List<LobbyPlayer> list = new ArrayList<>();
		list.add(me);
		for (String name : NAMES) {
			list.add(new LobbyPlayer(name, UUID.nameUUIDFromBytes(name.getBytes())));
		}
		return list;
	}

	private static List<PlayerInfo.ChatLog> chat() {
		String at = "2026-09-21T15:%02d:00.000Z";
		List<PlayerInfo.ChatLog> logs = new ArrayList<>();
		String[][] lines = {
				{"Public", "Hub", "anyone selling a hyperion? I have 900m and I really want one before the update comes out tomorrow evening"},
				{"Party", "The End", "ready when you are"},
				{"Public", "Hub", "gg"},
				{"Guild", "Hub", "the guild event starts in 10 minutes, please be online"},
				{"Public", "Unknown", "hello?"},
				{"Co-op", "Garden", "watering the crops now"},
				{"Public", "Hub", "lf carry f7 m7, paying 20m per run, need a healer and a mage that knows the rotation well"},
				{"Private", "Hub", "psst, check your inbox"},
				{"Public", "Hub", "nice"},
				{"Public", "Hub", "o/"}};
		for (int i = 0; i < lines.length; i++) {
			logs.add(new PlayerInfo.ChatLog(at.formatted(50 - i), lines[i][0], lines[i][1], lines[i][2]));
		}
		return logs;
	}

	/** The answer for a player: a rank, a Discord, meetings, networth and chat. Varies with the name so screenshots show several looks. */
	private static PlayerInfo answer(LobbyPlayer player) {
		return switch (player.name()) {
			case "Technoblade" -> new PlayerInfo(PlayerInfo.Status.FOUND, player.name(), null, null, 12, chat(), null, "YOUTUBER", null, "api_off");
			case "Dream" -> new PlayerInfo(PlayerInfo.Status.FOUND, player.name(), "dream_official", null, 1, List.of(), null, "MVP++", 350_000_000L, null);
			case "Alice" -> new PlayerInfo(PlayerInfo.Status.NOT_IN_DATABASE, null, "alice_on_discord", null, 0, List.of(), null, "VIP+", 12_500L, null);
			case "Bob_the_Builder" -> PlayerInfo.failed("Could not reach the website (connection refused).");
			case "Notch" -> new PlayerInfo(PlayerInfo.Status.FOUND, player.name(), null, null, 2, chat().subList(0, 2), null, "ADMIN", 999_999_999L, null);
			case "jeb_" -> new PlayerInfo(PlayerInfo.Status.FOUND, player.name(), null, null, 4, chat(), null, "MODERATOR", null, "no_profile");
			case "Dinnerbone" -> new PlayerInfo(PlayerInfo.Status.FOUND, player.name(), "a_really_long_discord_name_here_12345", null, 30, chat(), null, "HELPER", 1_200_000_000L, null);
			case "Grumm" -> new PlayerInfo(PlayerInfo.Status.FOUND, player.name(), null, null, 5, chat(), null, "MVP", 0L, null);
			case "Steve" -> new PlayerInfo(PlayerInfo.Status.FOUND, player.name(), null, null, 5, chat(), null, "VIP", 5L, null);
			default -> new PlayerInfo(PlayerInfo.Status.FOUND, player.name(), "greatsaiyaman8193", null, 3, chat(), null, "MVP+", 1_725_179_197L, null);
		};
	}

	/** Reads a private field of the screen, so the test can find where things are drawn. */
	private static Object field(Object target, String name) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static MouseButtonEvent click(double x, double y) {
		return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
	}

	/** Clicks the n-th row of the player list, the way a mouse click would reach it. */
	private static void clickRow(Minecraft client, int n) {
		Screen screen = McCompat.screen(client);
		ObjectSelectionList<?> list = (ObjectSelectionList<?>) field(screen, "list");
		ObjectSelectionList.Entry<?> row = list.children().get(n);
		screen.mouseClicked(click(row.getX() + 30, row.getY() + row.getHeight() / 2.0), false);
	}

	/** Bytes the game's render thread has allocated so far. */
	private static long renderThreadBytes() {
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.getName().equals("Render thread")) {
				return bean.getThreadAllocatedBytes(thread.threadId());
			}
		}
		return -1;
	}

	/** Lets the game run for a while and prints how much the render thread allocated per second and the frame rate. */
	private static void measure(ClientGameTestContext context, String label) {
		context.waitTicks(40);                                   // let the frame counter settle
		long bytes0 = renderThreadBytes();
		long time0 = System.nanoTime();
		context.waitTicks(200);
		long bytes1 = renderThreadBytes();
		double seconds = (System.nanoTime() - time0) / 1e9;
		int fps = context.computeOnClient(client -> client.getFps());
		System.out.printf("GAMETEST_PERF %-28s %8.0f KB/s allocated by the render thread, about %d frames/s, %.0f KB per frame%n",
				label, (bytes1 - bytes0) / 1024.0 / seconds, fps, (bytes1 - bytes0) / 1024.0 / Math.max(1, fps * seconds));
	}

	private static void setScale(ClientGameTestContext context, int scale) {
		context.runOnClient(client -> {
			client.options.guiScale().set(scale);
			client.resizeGui();
		});
		context.waitTicks(4);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(60);
			UUID myId = context.computeOnClient(client -> client.player.getUUID());
			String myName = context.computeOnClient(client -> client.player.getName().getString());
			me = new LobbyPlayer(myName, myId);

			// For the measurements the frame rate is uncapped and V-Sync is off, so a slower screen would show as fewer frames.
			context.runOnClient(client -> {
				client.options.enableVsync().set(false);
				client.options.framerateLimit().set(260);
			});
			measure(context, "game only (no screen)");

			// The window opens and fades in; then the list is shown with nobody selected.
			context.setScreen(() -> new PlayerInfoScreen(players(), player -> CompletableFuture.completedFuture(answer(player))));
			context.waitTicks(30);
			context.takeScreenshot("01-open-scale-auto");
			measure(context, "screen open, nobody selected");

			// Every kind of answer, at the default scale.
			int[] rows = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11};
			for (int row : rows) {
				context.runOnClient(client -> clickRow(client, row));
				context.waitTicks(12);
				context.takeScreenshot("02-answer-row-" + row);
			}

			// Click the Discord card of a linked player: the name must land on the clipboard and the toast appear.
			context.runOnClient(client -> clickRow(client, 0));
			context.waitTicks(10);
			context.runOnClient(client -> {
				Screen screen = McCompat.screen(client);
				int x = ((Integer) field(screen, "discordX0")) + 10;
				int y = ((Integer) field(screen, "statY0")) + 10;
				screen.mouseClicked(click(x, y), false);
			});
			context.waitTicks(6);
			context.takeScreenshot("03-copied-toast");
			String clipboard = context.computeOnClient(client -> client.keyboardHandler.getClipboard());
			if (!"greatsaiyaman8193".equals(clipboard)) {
				throw new AssertionError("the Discord name was not copied, the clipboard holds: " + clipboard);
			}
			context.waitTicks(45);
			context.takeScreenshot("04-toast-gone");

			// Search: click the box, type, and the list filters.
			context.runOnClient(client -> {
				Screen screen = McCompat.screen(client);
				int x = ((Integer) field(screen, "searchX0")) + 20;
				int y = ((Integer) field(screen, "searchY0")) + 8;
				screen.mouseClicked(click(x, y), false);
				for (char c : "bo".toCharArray()) {
					screen.charTyped(new CharacterEvent(c));
				}
			});
			context.waitTicks(8);
			context.takeScreenshot("05-search-bo");

			// A no-match search, then clear it.
			context.runOnClient(client -> {
				for (char c : "zzz".toCharArray()) {
					McCompat.screen(client).charTyped(new CharacterEvent(c));
				}
			});
			context.waitTicks(4);
			context.takeScreenshot("06-search-no-match");

			// The heaviest case: GUI scale 2 (a wide window with the 3D model), a player with a long chat log selected.
			setScale(context, 2);
			context.setScreen(() -> new PlayerInfoScreen(players(), player -> CompletableFuture.completedFuture(answer(player))));
			context.waitTicks(30);
			context.runOnClient(client -> clickRow(client, 0));
			measure(context, "screen + model, scale 2");
			setScale(context, 3);
			context.setScreen(() -> new PlayerInfoScreen(players(), player -> CompletableFuture.completedFuture(answer(player))));
			context.waitTicks(30);
			context.runOnClient(client -> clickRow(client, 0));
			measure(context, "screen, scale 3");

			// Other GUI scales, with a player selected.
			for (int scale : new int[] {1, 2, 3, 4}) {
				setScale(context, scale);
				context.setScreen(() -> new PlayerInfoScreen(players(), player -> CompletableFuture.completedFuture(answer(player))));
				context.waitTicks(30);
				context.runOnClient(client -> clickRow(client, 0));
				context.waitTicks(15);
				context.takeScreenshot("07-scale-" + scale);
			}
		}
	}
}
