package com.femboyclient.gametest;

import com.femboyclient.McCompat;
import com.femboyclient.FemboyClientConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Fires a real "Select an option: [Hunting?]" message whose click event is {@code ClickEvent.Custom} — exactly what real
 * Hypixel dialog options turned out to actually use ({@code skyblock:dialogue_response} with an NBT payload), not a plain
 * command — through the real Fabric chat event, and checks the real Auto Conversation listener handles it without error:
 * it builds and sends a real {@code ServerboundCustomClickActionPacket} over the real singleplayer connection, exactly the
 * way {@code Screen.defaultHandleGameClickEvent} does for a genuine click. There's nothing else to observe once it's sent
 * (the server only acts on this id on real Hypixel), so this proves the pipeline doesn't throw and stays headless — no
 * screen ever opens. Development only: {@code gradlew runClientGametest}.
 */
public final class NpcAutoCustomGameTest implements FabricClientGameTest {

	private static void expect(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
		System.out.println("GAMETEST_NPCCUSTOM ok: " + what);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			FemboyClientConfig.get().npcAuto = true;

			context.runOnClient(client -> {
				CompoundTag payload = new CompoundTag();
				payload.putString("npcId", "david");
				payload.putString("responseKey", "x_2");
				Style style = Style.EMPTY.withColor(ChatFormatting.GREEN)
						.withClickEvent(new ClickEvent.Custom(Identifier.parse("skyblock:dialogue_response"), Optional.of(payload)));
				Component message = Component.literal("Select an option: ").append(Component.literal("[Hunting?]").withStyle(style));
				ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(message, false);
			});

			// The real send happens after a short randomized delay (see NpcAutoAnswer.DELAY_MAX_NANOS); give it real time.
			long deadline = System.currentTimeMillis() + 3000;
			while (System.currentTimeMillis() < deadline) {
				context.waitTicks(2);
			}

			context.runOnClient(client -> expect(client.getConnection() != null, "the connection is still alive after sending a custom click"));
			context.runOnClient(client -> expect(McCompat.screen(client) == null, "no screen popped up: it stayed headless, exactly like a plain click"));
		}
	}
}
