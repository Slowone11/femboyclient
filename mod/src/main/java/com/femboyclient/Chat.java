package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Puts lines in the player's own chat window. Nothing here is sent to any server.
 *
 * Two things make this less trivial than it looks:
 *
 * 1. Status lines ("Connected to the server! Meow!") often become known while the
 *    game is still on the title screen, where nobody would see them, or just as
 *    the world loads, when the chat is cleared. So they wait until the player has
 *    been in a world for a few seconds. Only the newest one is kept, because an
 *    older "connected" is meaningless once a "disconnected" has followed it.
 *
 * 2. Fabric's message events fire for every line shown through the chat listener,
 *    including ours. isSendingOwn() lets the chat trackers ignore them, otherwise
 *    our own lines would be read as something the server said.
 *
 * All state is touched only on the client thread; other threads hand work over
 * with Minecraft.execute().
 */
public final class Chat {

	/** Ticks (20 per second) the player must be in a world before a waiting line is shown. */
	private static final int WAIT_TICKS = 60;

	private static Component pending;
	private static boolean sendingOwn;
	private static int ticksInWorld;

	private Chat() {
	}

	/** True while one of our own lines is being displayed. Client thread only. */
	static boolean isSendingOwn() {
		return sendingOwn;
	}

	/**
	 * Shows a status line, now if the player is in a world, otherwise as soon as
	 * they are. Safe to call from any thread.
	 */
	public static void notice(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}

		Component message = Component.literal(text);
		mc.execute(() -> {
			if (mc.player != null && ticksInWorld >= WAIT_TICKS) {
				show(mc, message);
			} else {
				pending = message;
			}
		});
	}

	/** Shows command output right away. Safe to call from any thread. */
	public static void showLater(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) {
			mc.execute(() -> show(mc, message));
		}
	}

	/** Called every client tick to release a waiting line once the world is ready. */
	public static void onTick(Minecraft mc) {
		if (mc.player == null) {
			ticksInWorld = 0;
			return;
		}

		if (ticksInWorld < WAIT_TICKS) {
			ticksInWorld++;
		}

		if (pending != null && ticksInWorld >= WAIT_TICKS) {
			Component message = pending;
			pending = null;
			show(mc, message);
		}
	}

	private static void show(Minecraft mc, Component message) {
		net.minecraft.client.multiplayer.chat.ChatListener listener = McCompat.chatListener(mc);
		if (listener == null) {
			return;
		}

		sendingOwn = true;
		try {
			listener.handleSystemMessage(message, false);
		} finally {
			sendingOwn = false;
		}
	}
}
