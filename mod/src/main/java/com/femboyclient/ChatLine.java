package com.femboyclient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a chat line into who said it, and what they said.
 *
 *   [248] ❤ [MVP+] Alice: hello there        public chat
 *   Party > [VIP] Alice: hi                   party chat
 *   Guild > [MVP+] Alice [Member]: gg         guild chat
 *   From [MVP+] Alice: psst                   a private message to you
 *   To [MVP+] Alice: on my way                a private message from you (the name is who it went to)
 *
 * A line only counts when everything in front of the name is a [tag] or a small
 * symbol, which is how the game writes ranks, levels and emblems. Server lines such as
 * "Bazaar Sold: 5 items" have plain words in front, so they are not read as chat.
 *
 * This only says what a line LOOKS like. For public chat the caller must also check that
 * the sender is really a player in the lobby (see PlayerChatCollector), because a
 * server line that happens to look like "Name: text" must not be taken for a person.
 * For a message you sent ("To Alice: ..."), the returned name is the person it was sent to.
 *
 * Kept free of Minecraft classes so it can be tested on its own.
 */
public final class ChatLine {

	/** Where a message was said. {@link #type()} is the name the website stores and filters by. */
	public enum Channel {
		PUBLIC, PARTY, GUILD, COOP, PRIVATE_FROM, PRIVATE_TO;

		/** The type sent to the website: PUBLIC, PARTY, GUILD, COOP, PRIVATE_FROM or PRIVATE_TO. */
		public String type() {
			return name();
		}
	}

	/**
	 * @param channel where it was said
	 * @param sender  the player's username (for PRIVATE_TO: the player you wrote to)
	 * @param text    what they said, tidied: no formatting codes, single spaces, at most MAX_TEXT characters
	 */
	public record Parsed(Channel channel, String sender, String text) {
	}

	/** Longest message kept. Hypixel's own limit is 256, and the website accepts a little more. */
	public static final int MAX_TEXT = 256;

	/** Any number of [tags] or small symbols, then the name, then any [tags] (a guild rank). */
	private static final Pattern SPEAKER = Pattern.compile(
			"(?:(?:\\[[^\\]]{1,24}\\]|[^A-Za-z0-9\\s\\[\\]:]{1,3})\\s*)*([A-Za-z0-9_]{3,16})(?:\\s*\\[[^\\]]{1,24}\\])*");

	private ChatLine() {
	}

	/** What a line says and who said it, or null if it is not a player's chat. */
	public static Parsed parse(String line) {
		if (line == null) {
			return null;
		}
		String text = line.strip();
		if (text.isEmpty() || text.length() > 1000) {
			return null;
		}

		Channel channel = Channel.PUBLIC;
		if (text.startsWith("Party > ")) {
			channel = Channel.PARTY;
			text = text.substring("Party > ".length());
		} else if (text.startsWith("Guild > ")) {
			channel = Channel.GUILD;
			text = text.substring("Guild > ".length());
		} else if (text.startsWith("Officer > ")) {
			channel = Channel.GUILD;
			text = text.substring("Officer > ".length());
		} else if (text.startsWith("Co-op > ")) {
			channel = Channel.COOP;
			text = text.substring("Co-op > ".length());
		} else if (text.startsWith("From ")) {
			channel = Channel.PRIVATE_FROM;
			text = text.substring("From ".length());
		} else if (text.startsWith("To ")) {
			channel = Channel.PRIVATE_TO;
			text = text.substring("To ".length());
		}

		// The speaker is everything before the first colon that is followed by a space.
		int colon = text.indexOf(": ");
		if (colon <= 0) {
			return null;
		}

		Matcher speaker = SPEAKER.matcher(text.substring(0, colon).strip());
		if (!speaker.matches()) {
			return null;
		}

		String said = tidy(text.substring(colon + 2));
		if (said.isEmpty()) {
			return null;
		}
		return new Parsed(channel, speaker.group(1), said);
	}

	/** Removes control characters, collapses runs of spaces, and cuts the text to MAX_TEXT characters. */
	static String tidy(String text) {
		StringBuilder clean = new StringBuilder();
		boolean space = true;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c) || Character.isISOControl(c)) {
				if (!space) {
					clean.append(' ');
					space = true;
				}
			} else {
				clean.append(c);
				space = false;
			}
		}

		String result = clean.toString().strip();
		return result.length() > MAX_TEXT ? result.substring(0, MAX_TEXT - 1) + "…" : result;
	}
}
