package com.femboyclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * What the website knows about one player, from GET /api/player/{uuid}. This is what the in-game
 * screen shows: their Discord, how often you have met them, and what they said.
 *
 * Kept free of Minecraft classes so it can be tested on its own.
 *
 * @param status       whether the answer holds data, the player is not in the database, or the request failed
 * @param name         the username as the database has it, or null
 * @param discord      the linked Discord name, or null if none is linked
 * @param discordError why the Discord lookup failed (the rest of the answer is still good), or null
 * @param timesMet     how many times you have met them
 * @param chatLogs     what they said, newest first
 * @param error        for status ERROR: a short reason to show the player, otherwise null
 * @param rank         their Hypixel rank as the website sends it ("MVP+", "YOUTUBER", "none", ...), or null if unknown
 * @param networth     their SkyBlock networth in coins, or null (see {@code networthNote})
 * @param networthNote why there is no networth: "api_off", "no_profile" or an error text; null when there is one
 */
public record PlayerInfo(Status status, String name, String discord, String discordError, int timesMet,
		List<ChatLog> chatLogs, String error, String rank, Long networth, String networthNote) {

	/** An answer without a rank or networth (a failure, or a test). */
	public PlayerInfo(Status status, String name, String discord, String discordError, int timesMet,
			List<ChatLog> chatLogs, String error) {
		this(status, name, discord, discordError, timesMet, chatLogs, error, null, null, null);
	}

	public enum Status { FOUND, NOT_IN_DATABASE, ERROR }

	/** One thing they said. {@code at} is the UTC time as the website sends it. */
	public record ChatLog(String at, String channel, String world, String message) {
	}

	/** A request that did not work. */
	public static PlayerInfo failed(String reason) {
		return new PlayerInfo(Status.ERROR, null, null, null, 0, List.of(), reason);
	}

	/** Turns the website's answer into a PlayerInfo. Never throws. */
	public static PlayerInfo fromResponse(WebClient.Response response) {
		if (!response.reachable()) {
			return failed("Could not reach the website (" + response.describe() + ").");
		}
		if (response.status() == 401) {
			return failed("The website does not know this account's key.");
		}
		if (!response.ok()) {
			return failed("The website answered " + response.describe() + ".");
		}
		try {
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			List<ChatLog> logs = new ArrayList<>();
			if (json.has("chatLogs") && json.get("chatLogs").isJsonArray()) {
				for (JsonElement item : json.getAsJsonArray("chatLogs")) {
					JsonObject log = item.getAsJsonObject();
					logs.add(new ChatLog(text(log, "at"), text(log, "channel"), text(log, "world"), text(log, "message")));
				}
			}
			boolean found = json.has("found") && json.get("found").getAsBoolean();
			return new PlayerInfo(found ? Status.FOUND : Status.NOT_IN_DATABASE, text(json, "name"), text(json, "discord"),
					text(json, "discordError"), json.has("timesMet") ? json.get("timesMet").getAsInt() : 0, List.copyOf(logs), null,
					text(json, "rank"), number(json, "networth"), text(json, "networthNote"));
		} catch (RuntimeException e) {
			return failed("The website sent something the mod could not read.");
		}
	}

	/** A whole-number field, or null if it is missing, null or not a number. */
	private static Long number(JsonObject json, String key) {
		JsonElement value = json.get(key);
		if (value == null || value.isJsonNull() || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		return value.getAsLong();
	}

	/** A text field, or null if it is missing or null. */
	private static String text(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value == null || value.isJsonNull() ? null : value.getAsString();
	}
}
