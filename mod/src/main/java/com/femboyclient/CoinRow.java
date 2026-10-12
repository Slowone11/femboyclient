package com.femboyclient;

import com.google.gson.JsonObject;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * One recorded coin change. This is the unit that goes into the CSV and gets
 * uploaded to the website.
 *
 * @param sessionId     id of the play session this belongs to
 * @param playerUuid    who the coins belong to
 * @param playerName    their name at the time
 * @param timestamp     when we noticed the change, with timezone offset
 * @param coins         purse total after the change
 * @param change        how much it moved, negative for a loss
 * @param causeLabel    classifier label, e.g. "bazaar" or "unknown"
 * @param causeMessage  the chat line we blamed, may be empty
 */
public record CoinRow(
		String sessionId,
		UUID playerUuid,
		String playerName,
		OffsetDateTime timestamp,
		long coins,
		long change,
		String causeLabel,
		String causeMessage) {

	/** The website rejects absurdly long messages, so cut them here first. */
	private static final int MAX_MESSAGE_CHARS = 500;

	/**
	 * The row as the website expects it. Field names are snake_case to match the
	 * CoinRowIn model in app.py.
	 */
	public JsonObject toJson() {
		String message = causeMessage == null ? "" : causeMessage;
		if (message.length() > MAX_MESSAGE_CHARS) {
			message = message.substring(0, MAX_MESSAGE_CHARS);
		}

		JsonObject json = new JsonObject();
		json.addProperty("session_id", sessionId);
		json.addProperty("player_uuid", playerUuid.toString());
		json.addProperty("player_name", playerName);
		json.addProperty("timestamp", timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		json.addProperty("coins", coins);
		json.addProperty("change", change);
		json.addProperty("cause_label", causeLabel);
		json.addProperty("cause_message", message);
		return json;
	}
}
