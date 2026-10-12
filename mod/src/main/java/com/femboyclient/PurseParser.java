package com.femboyclient;

import java.util.List;

/**
 * Finds and reads the purse line on the SkyBlock sidebar.
 *
 * Kept free of Minecraft classes so it can be tested on its own.
 */
public final class PurseParser {

	private PurseParser() {
	}

	/** Finds the "Purse:" or "Piggy:" line and returns its coin value, or null. */
	static Long findPurse(List<String> lines) {
		for (String line : lines) {
			if (line.startsWith("Purse:") || line.startsWith("Piggy:")) {
				return parseCoins(line.substring(line.indexOf(':') + 1));
			}
		}
		return null;
	}

	/**
	 * Parses the number after "Purse:" and returns it, or null if the text is not
	 * a plain whole number.
	 *
	 * Handles thousands separators, padding spaces (including non-breaking ones),
	 * and drops the "(+1,234)" recent-gain suffix SkyBlock sometimes appends.
	 * Anything else, such as an abbreviated "1.2M", returns null so we skip the
	 * reading rather than record a wrong value.
	 */
	static Long parseCoins(String raw) {
		String text = raw;

		int paren = text.indexOf('(');
		if (paren >= 0) {
			text = text.substring(0, paren);
		}

		StringBuilder digits = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= '0' && c <= '9') {
				digits.append(c);
			} else if (c == ',' || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
				// Thousands separators and padding are ignored.
				continue;
			} else {
				// Unexpected character, so we do not trust this reading.
				return null;
			}
		}

		if (digits.isEmpty()) {
			return null;
		}

		try {
			return Long.parseLong(digits.toString());
		} catch (NumberFormatException e) {
			// Far too many digits to be a real purse; ignore it.
			return null;
		}
	}
}
