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
 * Fires a real, styled "[NPC] Bank Teller: ... [Yes] [No]" chat message — an NPC line with no "Select an option:" marker
 * on it at all, just the "[NPC]" tag — through the real Fabric chat event, and checks the real Auto Conversation listener
 * still finds the clickable options and sends the green one on its own. Development only: {@code gradlew runClientGametest}.
 */
public final class NpcAutoTagGameTest implements FabricClientGameTest {

	private static void expect(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
		System.out.println("GAMETEST_NPCTAG ok: " + what);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			FemboyClientConfig.get().npcAuto = true;

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
				Component message = Component.literal("[NPC] Bank Teller: Have you ever heard about Sweep? ")
						.append(yes).append(Component.literal(" ")).append(no);
				ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(message, false);
			});

			long deadline = System.currentTimeMillis() + 5000;
			while (seenChat.isEmpty() && System.currentTimeMillis() < deadline) {
				context.waitTicks(2);
			}

			expect(!seenChat.isEmpty(), "Auto Conversation sent something on its own from a plain [NPC]-tagged line");
			expect(seenChat.stream().anyMatch(line -> line.endsWith("Yes")), "it sent the green option (\"Yes\"), saw: " + seenChat);
			expect(seenChat.stream().noneMatch(line -> line.endsWith("No")), "it did not send the non-green option (\"No\"), saw: " + seenChat);
		}
	}
}
