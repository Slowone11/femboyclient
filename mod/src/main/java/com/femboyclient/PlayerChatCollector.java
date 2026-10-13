package com.femboyclient;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Collects what players say, yours included, and sends it to the website in small batches. Part of the scanner.
 *
 * It only reads chat lines the game already received and sends nothing to the game
 * server. It runs in every world, all the time, while the mod is enabled.
 *
 * A message is only kept when it provably came from a person:
 *   - public chat: the sender must be a real player in your tab list right now, so a
 *     server line that looks like "Name: text" is never taken for a person;
 *   - party, guild, co-op and private messages: the game's own channel prefix says so.
 * Your own messages are kept too, in every channel: a line whose sender is your own name is yours. You are not
 * in your own tab-list name set, so public chat does not need you there. The website files them under your
 * account, not as a player. Private
 * messages you send ("To Alice: ...") are kept under the player you wrote to.
 * Every message is sent with its type: PUBLIC, PARTY, GUILD, COOP, PRIVATE_FROM or PRIVATE_TO. Every line is kept, a repeat
 * too (even one Hypixel or another mod printed twice).
 *
 * Runs on the client thread. Messages wait up to FLUSH_AFTER, so a busy lobby is
 * sent as one request every couple of seconds instead of one per line.
 */
public final class PlayerChatCollector {

	private static final Logger LOGGER = LoggerFactory.getLogger(FemboyClientMod.MOD_ID);

	/** How long a message may wait before the batch is sent. */
	static final Duration FLUSH_AFTER = Duration.ofSeconds(2);

	/** Send at once when this many are waiting, and never hold more than this. */
	static final int MAX_BATCH = 100;

	private record Pending(String player, String type, String text, Instant at) {
	}

	private final Consumer<JsonObject> sink;
	private final BooleanSupplier allowed;
	private final BooleanSupplier ownAllowed;
	private final Supplier<String> me;
	private final Supplier<Set<String>> lobbyNames;
	private final Supplier<String> world;

	private final List<Pending> pending = new ArrayList<>();
	private Instant oldest;
	private volatile int collected;

	/**
	 * @param sink       where a finished message goes (the website link)
	 * @param allowed    true while other players' chat may be collected (the mod is enabled)
	 * @param ownAllowed true while your own chat may be collected (the mod is enabled)
	 * @param me         your own account name
	 * @param lobbyNames the lower-case usernames of the real players in your tab list
	 * @param world      the SkyBlock world you are in, or null if it is not known
	 */
	public PlayerChatCollector(Consumer<JsonObject> sink, BooleanSupplier allowed, BooleanSupplier ownAllowed,
			Supplier<String> me, Supplier<Set<String>> lobbyNames, Supplier<String> world) {
		this.sink = sink;
		this.allowed = allowed;
		this.ownAllowed = ownAllowed;
		this.me = me;
		this.lobbyNames = lobbyNames;
		this.world = world;
	}

	/** Called with every chat message, without formatting codes. Never throws. */
	public void onLine(String message) {
		try {
			if (message == null) {
				return;
			}
			Instant now = Instant.now();
			for (String line : message.split("\\R")) {
				handle(line, now);
			}
			if (pending.size() >= MAX_BATCH) {
				flush();
			}
		} catch (RuntimeException e) {
			LOGGER.warn("player chat: could not read a chat line ({})", e.toString());
		}
	}

	void handle(String line, Instant now) {
		ChatLine.Parsed said = ChatLine.parse(line);
		if (said == null) {
			return;
		}

		String sender = said.sender();
		String mine = me.get();
		boolean yours = said.channel() == ChatLine.Channel.PRIVATE_TO || (mine != null && sender.equalsIgnoreCase(mine));
		if (yours) {
			if (!ownAllowed.getAsBoolean()) {
				return;
			}
		} else {
			if (!allowed.getAsBoolean()) {
				return;
			}
			if (said.channel() == ChatLine.Channel.PUBLIC && !lobbyNames.get().contains(sender.toLowerCase(Locale.ROOT))) {
				return;     // not someone in this lobby, so not a person talking
			}
		}

		if (pending.isEmpty()) {
			oldest = now;
		}
		pending.add(new Pending(sender, said.channel().type(), said.text(), now));
		collected++;
	}

	/** How many messages were collected since the game started, for the G menu. */
	public int collected() {
		return collected;
	}

	/** Called every client tick: sends the batch once the oldest message has waited long enough. */
	public void tick() {
		try {
			if (!pending.isEmpty() && oldest != null && Duration.between(oldest, Instant.now()).compareTo(FLUSH_AFTER) >= 0) {
				flush();
			}
		} catch (RuntimeException e) {
			LOGGER.warn("player chat: could not send ({})", e.toString());
		}
	}

	/** You left the server: send what is waiting. */
	public void onDisconnect() {
		try {
			flush();
		} catch (RuntimeException e) {
			LOGGER.warn("player chat: could not send ({})", e.toString());
		}
	}

	private void flush() {
		if (pending.isEmpty()) {
			return;
		}

		List<TrackerPayload.ChatEntry> batch = new ArrayList<>();
		for (Pending p : pending) {
			batch.add(new TrackerPayload.ChatEntry(p.player(), p.type(), p.text(), p.at()));
		}
		pending.clear();
		oldest = null;

		String where = world.get();
		LOGGER.info("player chat: {} message(s) from {}", batch.size(),
				batch.stream().map(TrackerPayload.ChatEntry::player).distinct().toList());
		sink.accept(TrackerPayload.chat(me.get(), where == null ? SkyBlockWorlds.UNKNOWN : where, batch));
	}
}
