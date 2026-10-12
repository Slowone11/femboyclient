package com.femboyclient;

import java.util.regex.Pattern;

/**
 * Decides whether a chat line was typed by a player.
 *
 * The party tracker uses it to tell a line someone typed apart from a server line.
 *
 * Hypixel sends player chat as ordinary system messages, so the event type alone
 * cannot be trusted. Instead we look at the shape of the line:
 *
 *   [371] * [MVP+] Name: hello     public chat, with level, emblem and rank
 *   Party > [VIP] Name: hi         party, guild and co-op channels
 *   From [MVP+] Name: hi           private messages
 *   To Name: hi
 *
 * A server line such as "[Bazaar] Sold 64x Enchanted Bone for 1,000 coins!" has no
 * "Name:" part, so it is kept.
 *
 * This errs towards calling something player chat.
 */
public final class PlayerChatFilter {

	/** Any [bracketed] group: SkyBlock levels, ranks, guild tags, emblems. */
	private static final Pattern BRACKET_GROUP = Pattern.compile("\\[[^\\]]*\\]");

	private PlayerChatFilter() {
	}

	/** True if the line looks like something a player typed. */
	public static boolean looksLikePlayerChat(String text) {
		String line = text.trim();

		// "From ..." and "To ..." only count as private messages when a name and
		// colon follow, so a server line like "To be claimed later" is kept.
		if (line.startsWith("From ")) {
			return nameThenColon(line.substring("From ".length()));
		}
		if (line.startsWith("To ")) {
			return nameThenColon(line.substring("To ".length()));
		}

		// Channel chat, e.g. "Party > Name: hi" or "Guild > Name: hi".
		int channel = line.indexOf(" > ");
		if (channel >= 0) {
			return nameThenColon(line.substring(channel + 3));
		}

		return nameThenColon(line);
	}

	/**
	 * True if the part before the first colon is exactly one player name.
	 *
	 * Hypixel decorates names heavily, so every bracketed group is removed first,
	 * decorative symbols are ignored, and exactly one username-shaped word must
	 * remain.
	 */
	private static boolean nameThenColon(String text) {
		// Brackets go first: they can contain colons of their own, and a rank tag
		// is never part of the name we are looking for.
		String stripped = BRACKET_GROUP.matcher(text).replaceAll(" ");

		int colon = stripped.indexOf(':');
		if (colon <= 0) {
			return false;
		}

		String name = null;
		for (String token : stripped.substring(0, colon).trim().split("\\s+")) {
			if (!hasAsciiLetterOrDigit(token)) {
				// A decoration such as the guild emblem between level and rank.
				continue;
			}
			if (name != null) {
				// Two real words before the colon, so this is a sentence like
				// "Flight Duration: 21:49:48", not "Name: message".
				return false;
			}
			name = token;
		}

		if (name == null || name.length() > 16) {
			return false;
		}

		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (!Character.isLetterOrDigit(c) && c != '_') {
				return false;
			}
		}
		return true;
	}

	/**
	 * True if the token contains at least one plain ASCII letter or digit.
	 *
	 * Minecraft usernames are ASCII only, so anything without an ASCII character
	 * is decoration rather than a word. This matters because some of Hypixel's
	 * guild emblems are real Unicode letters, for example the rune U+16DD, which
	 * Character.isLetterOrDigit would happily accept as part of a name.
	 */
	private static boolean hasAsciiLetterOrDigit(String token) {
		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt(i);
			boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
			if (ascii) {
				return true;
			}
		}
		return false;
	}
}
