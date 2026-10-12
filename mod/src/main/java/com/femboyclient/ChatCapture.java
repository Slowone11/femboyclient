package com.femboyclient;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Keeps a rolling buffer of the last few seconds of incoming chat, so that when
 * the purse changes we can look back and see what caused it.
 *
 * Read-only: we listen to messages the server already sent us and never reply.
 */
public final class ChatCapture {

	/** How far back the buffer reaches. */
	public static final Duration WINDOW = Duration.ofSeconds(10);

	/** Hard cap so a spammy lobby cannot grow the buffer without limit. */
	private static final int MAX_MESSAGES = 200;

	private final Deque<ChatMessage> buffer = new ArrayDeque<>();

	/** Hooks up the Fabric message events. Call once during client init. */
	public void register() {
		// Server-generated messages: "[Bazaar] Sold 64x ...", rewards, warnings.
		// On Hypixel, player chat also arrives through this event, so we still
		// run the player-chat check rather than trusting the event type.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return; // action bar text, not chat
			}
			add(message, false);
		});

		// Properly signed player chat messages. Always treated as player chat.
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, receptionTime) ->
				add(message, true));
	}

	private void add(Component message, boolean knownPlayerChat) {
		// Lines the mod prints itself ("Connected to the server! Meow!", command
		// output) pass through the same event. They are not game messages, so they
		// must never be blamed as the cause of a coin change.
		if (Chat.isSendingOwn()) {
			return;
		}

		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.chatLogging) {
			return;
		}

		String text = ScoreboardReader.strip(message.getString());
		if (text.isEmpty()) {
			return;
		}

		boolean playerChat = knownPlayerChat || PlayerChatFilter.looksLikePlayerChat(text);

		// Privacy: drop other people's chat unless it was explicitly switched on.
		// Dropped here means it is never stored, never written to CSV, never uploaded.
		if (playerChat && !config.includePlayerChat) {
			return;
		}

		synchronized (buffer) {
			buffer.addLast(new ChatMessage(Instant.now(), text));
			prune();
		}
	}

	/** Drops messages older than the window, and caps the buffer size. */
	private void prune() {
		Instant cutoff = Instant.now().minus(WINDOW);
		while (!buffer.isEmpty() && buffer.peekFirst().time().isBefore(cutoff)) {
			buffer.removeFirst();
		}
		while (buffer.size() > MAX_MESSAGES) {
			buffer.removeFirst();
		}
	}

	/** Returns the messages currently inside the window, oldest first. */
	public List<ChatMessage> recent() {
		synchronized (buffer) {
			prune();
			return new ArrayList<>(buffer);
		}
	}

	/** Forgets everything, e.g. when a session ends. */
	public void clear() {
		synchronized (buffer) {
			buffer.clear();
		}
	}
}
