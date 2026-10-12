package com.femboyclient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Picks the real players out of the tab list, the same ones you see when you press Tab.
 *
 * Hypixel fills the tab list with layout entries (the "Area:", "Server:" and
 * "Players (28)" lines). Each of those has a random profile name such as
 * "0hw2b27c53", and their profile names look just like usernames. Hypixel also adds
 * short-lived entries with random names for NPCs. Two checks separate them from real
 * players: a real account has a version 4 UUID, and the name SHOWN in the tab list
 * contains the username (a layout entry shows text like "Area: Village" instead).
 *
 * Kept free of Minecraft classes so it can be tested on its own.
 */
public final class TabList {

	/**
	 * One tab list entry.
	 *
	 * @param profileName the entry's internal name
	 * @param shownText   the text the tab list displays for it, with or without formatting codes
	 * @param uuidVersion the version digit of the entry's UUID (real accounts are 4)
	 * @param id          the entry's UUID, or null when it is not known
	 */
	public record Entry(String profileName, String shownText, int uuidVersion, java.util.UUID id) {

		public Entry(String profileName, String shownText, int uuidVersion) {
			this(profileName, shownText, uuidVersion, null);
		}
	}

	/** Real Minecraft accounts have version 4 UUIDs. Hypixel's NPCs and helper entries use another version. */
	public static final int ACCOUNT_UUID_VERSION = 4;
	private static final java.util.regex.Pattern NOT_NAME_CHARS = java.util.regex.Pattern.compile("[^A-Za-z0-9_]+");

	private TabList() {
	}

	/** True if this entry is a real player, meaning its username appears in the text shown for it. */
	public static boolean isPlayer(Entry entry) {
		String name = entry.profileName();
		if (!PlayerNames.isValid(name) || entry.uuidVersion() != ACCOUNT_UUID_VERSION) {
			return false;
		}

		String shown = ScoreboardReader.strip(entry.shownText() == null ? "" : entry.shownText());
		// Ranks, levels and icons sit around the name ("[MVP+] Steve", "Steve (Lv 5)"),
		// so compare word by word, splitting on anything that cannot be in a username.
		for (String word : NOT_NAME_CHARS.split(shown)) {
			if (word.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The usernames of the real players, valid, without your own, one each, sorted.
	 *
	 * @param own the player's own names (account name, in-world name). Nulls are ignored.
	 */
	public static List<String> players(Collection<Entry> entries, String... own) {
		List<String> names = new ArrayList<>();
		for (Entry entry : entries) {
			if (isPlayer(entry)) {
				names.add(entry.profileName());
			}
		}
		return PlayerNames.clean(names, own);
	}

	/**
	 * The real players with their UUIDs, for the in-game screen: valid, without you, one each, sorted by name.
	 * A player whose UUID is not known is left out.
	 */
	public static List<LobbyPlayer> lobbyPlayers(Collection<Entry> entries, String... own) {
		java.util.Map<String, java.util.UUID> ids = new java.util.HashMap<>();
		for (Entry entry : entries) {
			if (isPlayer(entry) && entry.id() != null) {
				ids.putIfAbsent(entry.profileName().toLowerCase(java.util.Locale.ROOT), entry.id());
			}
		}

		List<LobbyPlayer> players = new ArrayList<>();
		for (String name : players(entries, own)) {
			java.util.UUID id = ids.get(name.toLowerCase(java.util.Locale.ROOT));
			if (id != null) {
				players.add(new LobbyPlayer(name, id));
			}
		}
		return players;
	}

	/** How many entries have each UUID version, for the log, for example {2=80, 4=20}. */
	public static java.util.Map<Integer, Integer> versionCounts(Collection<Entry> entries) {
		java.util.Map<Integer, Integer> counts = new java.util.TreeMap<>();
		for (Entry entry : entries) {
			counts.merge(entry.uuidVersion(), 1, Integer::sum);
		}
		return counts;
	}

	/** The text shown for each entry, used to read the "Area:" line. */
	public static List<String> shownTexts(Collection<Entry> entries) {
		List<String> texts = new ArrayList<>();
		for (Entry entry : entries) {
			if (entry.shownText() != null) {
				texts.add(entry.shownText());
			}
		}
		return texts;
	}
}
