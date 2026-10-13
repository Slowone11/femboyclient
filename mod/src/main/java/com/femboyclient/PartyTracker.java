package com.femboyclient;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Notices who joins your party and reports each new member to the website.
 *
 * It only reads chat lines the game already received, and sends nothing to the game
 * server. Only usernames and the time are reported, never the chat line. It works
 * whenever the mod is enabled (the scanner is always on), like the rest of
 * Lobby Networth, and it does nothing about invites: see PartyChat.
 *
 * The chat hook lives in FemboyClientMod. All of this runs on the client thread.
 */
public final class PartyTracker {

	private static final Logger LOGGER = LoggerFactory.getLogger(FemboyClientMod.MOD_ID);

	/** After this many lines that mention a party but were not understood, stop logging them. */
	private static final int MAX_UNDERSTOOD_LOGS = 25;

	private final PartyState state = new PartyState();
	private final Consumer<JsonObject> sink;
	private final BooleanSupplier allowed;
	private final Supplier<String> me;
	private int notUnderstood;

	/**
	 * @param sink    where a finished message goes (the website link)
	 * @param allowed true while party tracking may run (the mod is enabled)
	 * @param me      your own account name
	 */
	public PartyTracker(Consumer<JsonObject> sink, BooleanSupplier allowed, Supplier<String> me) {
		this.sink = sink;
		this.allowed = allowed;
		this.me = me;
	}

	/**
	 * Called with every chat message, without formatting codes. Never throws. A message
	 * that holds several lines (the party list is one) is read a line at a time.
	 */
	public void onLine(String message) {
		try {
			if (message != null && allowed.getAsBoolean()) {   // off by default: skip the split for every chat line
				for (String line : message.split("\\R")) {
					handle(line, Instant.now());
				}
			}
		} catch (RuntimeException e) {
			LOGGER.warn("party tracker: could not read a chat line ({})", e.toString());
		}
	}

	void handle(String line, Instant now) {
		if (line == null || !allowed.getAsBoolean()) {
			return;
		}

		PartyChat.Event event = PartyChat.parse(line);
		if (event == null) {
			noteIfPartyRelated(line);
			return;
		}

		String name = me.get();
		List<String> joined = state.apply(event, name);
		if (joined.isEmpty()) {
			return;
		}

		LOGGER.info("party tracker: in your party: {}", joined);
		sink.accept(TrackerPayload.party(name, joined, now));
	}

	/** You left the server, so what we knew about the party may be out of date. */
	public void onDisconnect() {
		state.clear();
	}

	/**
	 * Help fix the message patterns: log the game's own lines that mention a party but
	 * were not understood. Lines that look like a player's chat are skipped.
	 */
	private void noteIfPartyRelated(String line) {
		if (notUnderstood >= MAX_UNDERSTOOD_LOGS || !line.toLowerCase().contains("party")
				|| PlayerChatFilter.looksLikePlayerChat(line)) {
			return;
		}
		notUnderstood++;
		LOGGER.info("party tracker: ignored a line that mentions a party (an invite, or a message not in the table): {}", line.strip());
	}
}
