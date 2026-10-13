package com.femboyclient;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

/**
 * The Proxy button in the bottom left corner of the server list. It opens {@link ProxyScreen} and shows the applied
 * proxy's state: "Connected" in green once its test worked, so the player sees it before joining a server.
 */
final class ProxyButton {

	/** Width of the server list's footer button rows, which sit in the middle of the bottom of the screen. */
	private static final int FOOTER_WIDTH = 308;

	private ProxyButton() {
	}

	static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof JoinMultiplayerScreen)) {
				return;
			}
			ProxyState.refresh(false);
			int buttonWidth = Math.max(50, Math.min(90, (width - FOOTER_WIDTH) / 2 - 12));
			Button button = Button.builder(label(), b -> client.setScreenAndShow(new ProxyScreen(screen)))
					.bounds(6, height - 26, buttonWidth, 20)
					.build();
			Screens.getWidgets(screen).add(button);
			ScreenEvents.afterTick(screen).register(s -> button.setMessage(label()));
		});
	}

	static Component label() {
		return switch (ProxyState.status()) {
			case CONNECTED -> Component.literal("Connected").withStyle(ChatFormatting.GREEN);
			case TESTING -> Component.literal("Testing...").withStyle(ChatFormatting.YELLOW);
			case FAILED -> Component.literal("Proxy failed").withStyle(ChatFormatting.RED);
			default -> Component.literal("Proxy");
		};
	}
}
