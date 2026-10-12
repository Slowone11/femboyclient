package com.femboyclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The JSON the mod sends to POST /tracker/snapshot.
 *
 * Only usernames, times and the SkyBlock world name (for example "Hub") are
 * included. No chat, no addresses, no server or lobby identifiers.
 */
public final class TrackerPayload {

	/** The website accepts at most this many names in one request. */
	public static final int MAX_PLAYERS = 500;

	private TrackerPayload() {
	}

	/**
	 * @param me           the account that saw these players
	 * @param world        the SkyBlock world name, or "Unknown"
	 * @param players      usernames seen
	 * @param timestamp    when they were seen (sent as UTC)
	 * @param visitStarted when the current lobby visit began. It is how the website
	 *                     tells visits apart, and it is the same in every message of
	 *                     one visit, so a retried message cannot create a second visit.
	 */
	public static JsonObject build(String me, String world, List<String> players, Instant timestamp,
			Instant visitStarted) {
		JsonArray names = new JsonArray();
		players.forEach(names::add);

		JsonObject json = new JsonObject();
		json.addProperty("me", me);
		json.addProperty("world", world);
		json.add("players", names);
		json.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp));
		json.addProperty("visit_started", DateTimeFormatter.ISO_INSTANT.format(visitStarted));
		return json;
	}

	/**
	 * The message for POST /tracker/party: players who have just been confirmed to be in
	 * your party. Only usernames and the time, never the chat line that showed it.
	 */
	public static JsonObject party(String me, List<String> players, Instant timestamp) {
		JsonArray names = new JsonArray();
		players.forEach(names::add);

		JsonObject json = new JsonObject();
		json.addProperty("me", me);
		json.add("players", names);
		json.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp));
		return json;
	}

	/** One thing a player said. {@code type} is PUBLIC, PARTY, GUILD, COOP, PRIVATE_FROM or PRIVATE_TO. */
	public record ChatEntry(String player, String type, String text, Instant at) {
	}

	/**
	 * The message for POST /tracker/chat: what players said, with who, where and when. The text is
	 * only what they typed, tidied, without the rank or level in front of it.
	 */
	public static JsonObject chat(String me, String world, List<ChatEntry> entries) {
		JsonArray messages = new JsonArray();
		for (ChatEntry entry : entries) {
			JsonObject one = new JsonObject();
			one.addProperty("player", entry.player());
			one.addProperty("type", entry.type());
			one.addProperty("text", entry.text());
			one.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(entry.at()));
			messages.add(one);
		}

		JsonObject json = new JsonObject();
		json.addProperty("me", me);
		json.addProperty("world", world);
		json.add("messages", messages);
		return json;
	}

	/** True for a Player Finder report ({target, run, status, hops, ...}). */
	public static boolean isFinder(JsonObject payload) {
		return payload.has("status");
	}

	/** True for a party message. A lobby message has visit_started, a chat message has messages, a finder report has status. A party message has none. */
	public static boolean isParty(JsonObject payload) {
		return !payload.has("visit_started") && !payload.has("messages") && !payload.has("status");
	}

	/** True for a chat message. */
	public static boolean isChat(JsonObject payload) {
		return payload.has("messages");
	}

	/** Where a queued message is sent. */
	public static String path(JsonObject payload) {
		return isChat(payload) ? "/tracker/chat"
				: isFinder(payload) ? "/finder/report" : isParty(payload) ? "/tracker/party" : "/tracker/snapshot";
	}

	/** A short name for the log: "player tracker", "party tracker" or "player chat". */
	public static String label(JsonObject payload) {
		return isChat(payload) ? "player chat" : isFinder(payload) ? "player finder"
				: isParty(payload) ? "party tracker" : "player tracker";
	}

	/**
	 * How many players (or chat messages) a queued message holds, for the log. 1 for anything without a list, such as a Crystal
	 * Hollows message an older jar left in the queue on disk (the website no longer takes those, so it refuses and drops it).
	 */
	public static int size(JsonObject payload) {
		if (isFinder(payload)) {
			return 1;
		}
		com.google.gson.JsonElement list = payload.get(isChat(payload) ? "messages" : "players");
		return list != null && list.isJsonArray() ? list.getAsJsonArray().size() : 1;
	}
}
