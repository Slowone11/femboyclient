package com.femboyclient;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

import java.util.Set;

/**
 * Connects the Player Finder's hop loop (FinderHop) to the real game and the website.
 *
 * This is where the Player Finder types its commands. It only ever calls sendCommand with "is" or
 * "warp <name>", chosen by FinderHop from FinderWarps, and only while a search is running. All of it
 * runs on the client thread.
 */
public final class FinderGame implements FinderHop.Env {

	@Override
	public boolean connected() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.level != null && mc.getConnection() != null;
	}

	@Override
	public String world() {
		return FemboyClientMod.PLAYER_TRACKER.currentWorld();
	}

	@Override
	public Set<String> lobbyNames() {
		return FemboyClientMod.PLAYER_TRACKER.lobbyNames();
	}

	@Override
	public void sendCommand(String command) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.sendCommand(command);
		}
	}

	@Override
	public void say(String text) {
		Component line = Component.literal("[Finder] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(text).withStyle(ChatFormatting.WHITE));
		Chat.showLater(line);
	}

	@Override
	public void report(JsonObject json) {
		FemboyClientMod.SERVER.enqueueTracker(json);
	}

	@Override
	public long nanos() {
		return System.nanoTime();
	}
}
