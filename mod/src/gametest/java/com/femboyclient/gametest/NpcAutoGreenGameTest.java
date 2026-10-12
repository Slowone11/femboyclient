package com.femboyclient.gametest;

import com.femboyclient.FemboyClientConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fires a real, styled "Select an option: [Yes] [No]" chat message (green Yes, red No) through the real Fabric chat event, and
 * checks the real Auto Conversation listener sends "Yes" (not "No") to the server on its own, using the game's own
 * Component/Style/ClickEvent classes end to end, not a hand-built fake. Development only: {@code gradlew runClientGametest}.
 */
public final class NpcAutoGreenGameTest implements FabricClientGameTest {

	private static void expect(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
		System.out.println("GAMETEST_NPCGREEN ok: " + what);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			FemboyClientConfig.get().npcAuto = true;

			// A fresh singleplayer world with only this player: any chat message that comes back is this test's own send,
			// round-tripped through the integrated server exactly like a real answer would be.
			List<String> seenChat = new ArrayList<>();
			context.runOnClient(client -> ClientReceiveMessageEvents.CHAT.register(
					(message, signed, sender, params, receptionTime) -> seenChat.add(message.getString())));

			context.runOnClient(client -> {
				net.minecraft.network.chat.Style green = net.minecraft.network.chat.Style.EMPTY
						.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent.RunCommand("Yes"));
				net.minecraft.network.chat.Style red = net.minecraft.network.chat.Style.EMPTY
						.withColor(ChatFormatting.RED).withClickEvent(new ClickEvent.RunCommand("No"));
				Component yes = Component.literal("[Yes]").withStyle(green);
				Component no = Component.literal("[No]").withStyle(red);
				Component message = Component.literal("Select an option: ").append(yes).append(Component.literal(" ")).append(no);
				ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(message, false);
			});

			// The real send happens after a short randomized delay (see NpcAutoAnswer.DELAY_MAX_NANOS) and a real chat send
			// round-trips through the integrated server, so give it real time, not just ticks.
			long deadline = System.currentTimeMillis() + 5000;
			while (seenChat.isEmpty() && System.currentTimeMillis() < deadline) {
				context.waitTicks(2);
			}

			expect(!seenChat.isEmpty(), "Auto Conversation sent something on its own");
			expect(seenChat.stream().anyMatch(line -> line.endsWith("Yes")), "it sent the green option (\"Yes\"), saw: " + seenChat);
			expect(seenChat.stream().noneMatch(line -> line.endsWith("No")), "it did not send the non-green option (\"No\"), saw: " + seenChat);
		}
	}
}
