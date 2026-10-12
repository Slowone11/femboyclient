package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.concurrent.CompletableFuture;

/**
 * Does the commands sent from the page in the real game: quit, or say a chat message.
 *
 * ServerLink asks for them on a background thread; everything here is handed to the client thread with
 * Minecraft.execute(), so the game never waits for the network. A chat message is sent as plain chat only,
 * never as a command: ServerLink and the website both refuse anything starting with "/", and so does this.
 */
public final class RemoteCommands implements ServerLink.CommandRunner {

	@Override
	public CompletableFuture<String> run(ServerLink.RemoteCommand command) {
		CompletableFuture<String> outcome = new CompletableFuture<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			outcome.complete("the game is not running");
			return outcome;
		}

		mc.execute(() -> {
			try {
				switch (command.type()) {
					case "QUIT" -> {
						outcome.complete(null);
						mc.stop();
					}
					case "CHAT" -> outcome.complete(say(mc, command.payload()));
					default -> outcome.complete("unknown command");
				}
			} catch (RuntimeException e) {
				outcome.complete("the game could not do it");
			}
		});
		return outcome;
	}

	/** Sends a plain chat line. Returns null when sent, or why it was not. Client thread only. */
	private static String say(Minecraft mc, String text) {
		if (text == null || text.isBlank() || text.startsWith("/")) {
			return "only chat messages can be sent, not commands";
		}
		ClientPacketListener connection = mc.getConnection();
		if (mc.player == null || connection == null) {
			return "not in a world";
		}
		connection.sendChat(text);
		return null;
	}
}
