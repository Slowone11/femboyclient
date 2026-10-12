package com.femboyclient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/** Connects {@link NpcAutoAnswer} to the real game: the three ways a click can act, and a local chat hint. */
public final class NpcAutoGame implements NpcAutoAnswer.Env {

	@Override
	public void sendCommand(String command) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.sendCommand(command);
		}
	}

	@Override
	public void sendChat(String text) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.sendChat(text);
		}
	}

	@Override
	public void sendCustomClick(Identifier id, Optional<Tag> payload) {
		// Exactly what Screen.defaultHandleGameClickEvent does for a ClickEvent.Custom: send the id and payload back as-is.
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.send(new ServerboundCustomClickActionPacket(id, payload));
		}
	}

	@Override
	public void say(String text) {
		Chat.showLater(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}

	@Override
	public long nanos() {
		return System.nanoTime();
	}
}
