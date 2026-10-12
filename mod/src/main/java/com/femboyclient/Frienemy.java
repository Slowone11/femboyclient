package com.femboyclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A player tagged friendly or enemy, from GET /tracker/frienemies. The tag and note are shared across every
 * account, like the rest of the tracker database.
 *
 * Kept free of Minecraft classes so it can be tested on its own.
 *
 * @param id     the tracker database's id for this player, used to untag them
 * @param uuid   their Minecraft UUID, or null if the website has not linked one yet
 * @param name   their username as last tagged
 * @param tag    "friendly" or "enemy"
 * @param notes  why they are tagged that way, or empty
 */
public record Frienemy(int id, UUID uuid, String name, String tag, String notes) {

	public boolean friendly() {
		return "friendly".equals(tag);
	}

	public boolean enemy() {
		return "enemy".equals(tag);
	}

	/** Turns the website's answer into a list of Frienemy. Never throws; an answer it cannot read becomes an empty list. */
	public static List<Frienemy> parseList(String body) {
		List<Frienemy> out = new ArrayList<>();
		try {
			JsonElement root = JsonParser.parseString(body);
			if (!root.isJsonArray()) {
				return List.of();
			}
			for (JsonElement element : root.getAsJsonArray()) {
				Frienemy one = parseOne(element.getAsJsonObject());
				if (one != null) {
					out.add(one);
				}
			}
		} catch (RuntimeException e) {
			return List.of();
		}
		return List.copyOf(out);
	}

	/** One entry from a POST /tracker/frienemies answer (a single object, not a list). Null if it cannot be read. */
	public static Frienemy parseOne(String body) {
		try {
			return parseOne(JsonParser.parseString(body).getAsJsonObject());
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** One entry, or null if it is missing what it needs (an id, a name or a recognised tag). */
	private static Frienemy parseOne(JsonObject json) {
		if (!json.has("id") || !json.has("name") || !json.has("tag")) {
			return null;
		}
		String tag = json.get("tag").getAsString();
		if (!"friendly".equals(tag) && !"enemy".equals(tag)) {
			return null;
		}
		return new Frienemy(json.get("id").getAsInt(), uuid(json), json.get("name").getAsString(), tag, notes(json));
	}

	private static UUID uuid(JsonObject json) {
		JsonElement value = json.get("uuid");
		if (value == null || value.isJsonNull()) {
			return null;
		}
		try {
			return UUID.fromString(value.getAsString());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String notes(JsonObject json) {
		JsonElement value = json.get("notes");
		return value == null || value.isJsonNull() ? "" : value.getAsString();
	}

	/**
	 * The tagged players who are in this lobby right now, matched by UUID first and, for a tagged player the
	 * website has not linked a UUID to yet, by username.
	 */
	public static List<Frienemy> presentIn(List<Frienemy> tagged, List<LobbyPlayer> lobby) {
		List<Frienemy> out = new ArrayList<>();
		for (LobbyPlayer player : lobby) {
			for (Frienemy one : tagged) {
				if (one.uuid() != null ? one.uuid().equals(player.id()) : one.name().equalsIgnoreCase(player.name())) {
					out.add(one);
					break;
				}
			}
		}
		return List.copyOf(out);
	}
}
