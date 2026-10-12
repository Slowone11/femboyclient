package com.femboyclient;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Collects what other players say, and sends it to the website in small batches.
 *
 * It only reads chat lines the game already received and sends nothing to the game
 * server. It runs while the player scanner is on (and only in the world the scanner is
 * set to), and it can be switched off with "playerChat=false" in the config file.
 *
 * A message is only kept when it provably came from a person:
 *   - public chat: the sender must be a real player in your tab list right now, so a
 *     server line that looks like "Name: text" is never taken for a person;
 *   - party, guild, co-op and private messages: the game's own channel prefix says so.
 * Your own messages are kept too. The website files them on your own node, not as a player. Private
 * messages you send ("To Alice: ...") are kept under the player you wrote to.
 * Every message is sent with its type: PUBLIC, PARTY, GUILD, COOP, PRIVATE_FROM or PRIVATE_TO. The same message from the same
 * player and type arriving again within 2 seconds is dropped (Hypixel and other mods sometimes print a line twice).
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

	/** The same message from the same player and type inside this time is a repeat. */
	static final Duration DUPLICATE_WINDOW = Duration.ofSeconds(2);

	/** When each recent message was kept, to spot repeats. Cleaned when it grows. */
	private static final int RECENT_LIMIT = 256;

	private record Pending(String player, String type, String text, Instant at) {
	}

	private final Consumer<JsonObject> sink;
	private final BooleanSupplier allowed;
	private final Supplier<String> me;
	private final Supplier<Set<String>> lobbyNames;
	private final Supplier<String> world;

	private final List<Pending> pending = new ArrayList<>();
	private final Map<String, Instant> recent = new HashMap<>();
	private Instant oldest;

	/**
	 * @param sink       where a finished message goes (the website link)
	 * @param allowed    true while chat collection may run (scanner on, right world, not switched off)
	 * @param me         your own account name
	 * @param lobbyNames the lower-case usernames of the real players in your tab list
	 * @param world      the SkyBlock world you are in, or null if it is not known
	 */
	public PlayerChatCollector(Consumer<JsonObject> sink, BooleanSupplier allowed, Supplier<String> me,
			Supplier<Set<String>> lobbyNames, Supplier<String> world) {
		this.sink = sink;
		this.allowed = allowed;
		this.me = me;
		this.lobbyNames = lobbyNames;
		this.world = world;
	}

	/** Called with every chat message, without formatting codes. Never throws. */
	public void onLine(String message) {
		try {
			if (message == null || !allowed.getAsBoolean()) {
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
		if (said.channel() == ChatLine.Channel.PUBLIC && !lobbyNames.get().contains(sender.toLowerCase(Locale.ROOT))) {
			return;     // not someone in this lobby, so not a person talking
		}

		if (isRepeat(said.channel().type() + "|" + sender.toLowerCase(Locale.ROOT) + "|" + said.text(), now)) {
			return;
		}

		if (pending.isEmpty()) {
			oldest = now;
		}
		pending.add(new Pending(sender, said.channel().type(), said.text(), now));
	}

	/** True if this message was already kept less than {@link #DUPLICATE_WINDOW} ago. A message that is kept is remembered. */
	private boolean isRepeat(String key, Instant now) {
		Instant last = recent.get(key);
		if (last != null && Duration.between(last, now).compareTo(DUPLICATE_WINDOW) < 0) {
			return true;
		}
		recent.put(key, now);
		if (recent.size() > RECENT_LIMIT) {
			recent.values().removeIf(kept -> Duration.between(kept, now).compareTo(DUPLICATE_WINDOW) >= 0);
		}
		return false;
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
