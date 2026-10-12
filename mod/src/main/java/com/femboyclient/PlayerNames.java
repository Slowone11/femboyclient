package com.femboyclient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the raw names in the tab list into the usernames worth reporting.
 *
 * Kept free of Minecraft classes so it can be tested on its own.
 */
public final class PlayerNames {

	/** A real Minecraft username: 3 to 16 letters, numbers or underscores. */
	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");

	private PlayerNames() {
	}

	/** True if the text is a valid Minecraft username. */
	public static boolean isValid(String name) {
		return name != null && VALID.matcher(name).matches();
	}

	/**
	 * The usernames worth reporting: valid ones only, each once whatever its
	 * capitalisation, without the player's own name, sorted so the result is stable.
	 *
	 * Servers pad the tab list with fake entries such as blank lines or "!A-a"
	 * that are used for layout, and the validity check drops those.
	 *
	 * @param own the player's own names (account name, in-world name). Nulls are ignored.
	 */
	public static List<String> clean(Collection<String> raw, String... own) {
		Set<String> skip = new HashSet<>();
		for (String name : own) {
			if (name != null) {
				skip.add(name.toLowerCase(Locale.ROOT));
			}
		}

		Set<String> seen = new HashSet<>();
		List<String> result = new ArrayList<>();
		for (String name : raw) {
			if (!isValid(name)) {
				continue;
			}
			String key = name.toLowerCase(Locale.ROOT);
			if (!skip.contains(key) && seen.add(key)) {
				result.add(name);
			}
		}

		result.sort(String.CASE_INSENSITIVE_ORDER);
		return result;
	}
}
