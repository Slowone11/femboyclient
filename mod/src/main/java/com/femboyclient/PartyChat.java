package com.femboyclient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's party messages and says who is really in your party.
 *
 * Only messages that prove someone IS (or is no longer) in the party count:
 *
 *   "[MVP+] Alice joined the party."          Alice is in the party
 *   "You have joined [VIP] Bob's party!"      Bob is in the party
 *   "You'll be partying with: A, [VIP] B"     A and B are in the party
 *   "Party > [MVP+] Alice: hi"                Alice is in the party (only members can speak there)
 *   "Party Members: A ● [VIP] B ●"            the /party list output
 *   "[MVP+] Alice has left the party."        Alice is gone
 *   "You left the party." and disbands        everyone is gone
 *
 * Invites and requests never match anything here ("X has invited you to join their
 * party!", "You invited X to your party!", "The party invite from X has expired"),
 * so an invite can never be mistaken for a member.
 *
 * Every pattern must match the WHOLE line. That is what stops a player from typing
 * "Bob joined the party." in public chat: their line starts with their own name and
 * a colon, so it does not fit.
 *
 * Only usernames are taken out of a line. The line itself is never kept or sent.
 * Kept free of Minecraft classes so it can be tested on its own.
 */
public final class PartyChat {

	/** What a line says about the party. */
	public enum Kind {
		/** These players are in the party right now. */
		PRESENT,
		/** This player left or was removed. */
		LEFT,
		/** You are no longer in a party, so nobody is. */
		CLEAR
	}

	/** One thing a line told us. */
	public record Event(Kind kind, List<String> names) {
	}

	private PartyChat() {
	}

	/** Any number of [bracketed] ranks or tags before a name, for example "[MVP+] ". */
	private static final String RANK = "(?:\\[[^\\]]{1,24}\\]\\s*)*";
	private static final String NAME = "([A-Za-z0-9_]{3,16})";

	// Someone is in the party.
	private static final Pattern YOU_JOINED = Pattern.compile("You have joined " + RANK + NAME + "'s party!");
	private static final Pattern PARTYING_WITH = Pattern.compile("You'll be partying with: (.+)");
	private static final Pattern JOINED = Pattern.compile(RANK + NAME + " joined the party\\.");
	private static final Pattern PARTY_CHAT = Pattern.compile("Party > " + RANK + NAME + ":\\s.*");
	private static final Pattern LIST_LINE = Pattern.compile("Party (?:Leader|Moderators|Members): (.+)");
	private static final Pattern PROMOTED = Pattern.compile(
			RANK + NAME + " has promoted " + RANK + NAME + " to Party (?:Leader|Moderator)");
	private static final Pattern TRANSFERRED = Pattern.compile(
			"The party was transferred to " + RANK + NAME + " by " + RANK + NAME);

	// Someone is gone.
	private static final Pattern LEFT = Pattern.compile(RANK + NAME + " has left the party\\.");
	private static final Pattern REMOVED = Pattern.compile(RANK + NAME + " has been removed from the party\\.");
	private static final Pattern DISCONNECTED = Pattern.compile(
			RANK + NAME + " was removed from your party because they disconnected\\.");

	// You are out of the party.
	private static final Pattern YOU_LEFT = Pattern.compile("You left the party\\.");
	private static final Pattern YOU_KICKED = Pattern.compile("You have been kicked from the party by " + RANK + NAME + "\\.?");
	private static final Pattern YOU_REMOVED = Pattern.compile("You have been removed from the party\\.?");
	private static final Pattern DISBANDED = Pattern.compile("The party was disbanded.*");
	private static final Pattern DISBANDED_BY = Pattern.compile(RANK + NAME + " has disbanded the party!?");
	private static final Pattern NOT_IN_PARTY = Pattern.compile("You are not (?:currently )?in a party\\.?");

	/**
	 * What a chat line says about the party, or null if it says nothing about who is in it.
	 *
	 * @param line one chat line, without formatting codes
	 */
	public static Event parse(String line) {
		if (line == null) {
			return null;
		}
		String text = line.strip();
		if (text.isEmpty() || text.length() > 400) {
			return null;
		}

		Matcher m;
		if ((m = YOU_JOINED.matcher(text)).matches()) {
			return present(m.group(1));
		}
		if ((m = PARTYING_WITH.matcher(text)).matches()) {
			return present(namesIn(m.group(1), ","));
		}
		if ((m = JOINED.matcher(text)).matches()) {
			return present(m.group(1));
		}
		if ((m = PARTY_CHAT.matcher(text)).matches()) {
			return present(m.group(1));
		}
		if ((m = LIST_LINE.matcher(text)).matches()) {
			return present(namesIn(m.group(1), "●"));
		}
		if ((m = PROMOTED.matcher(text)).matches()) {
			return present(m.group(1), m.group(2));
		}
		if ((m = TRANSFERRED.matcher(text)).matches()) {
			return present(m.group(1), m.group(2));
		}

		if ((m = LEFT.matcher(text)).matches() || (m = REMOVED.matcher(text)).matches()
				|| (m = DISCONNECTED.matcher(text)).matches()) {
			return isReal(m.group(1)) ? new Event(Kind.LEFT, List.of(m.group(1))) : null;
		}

		if (YOU_LEFT.matcher(text).matches() || YOU_KICKED.matcher(text).matches()
				|| YOU_REMOVED.matcher(text).matches() || DISBANDED.matcher(text).matches()
				|| DISBANDED_BY.matcher(text).matches() || NOT_IN_PARTY.matcher(text).matches()) {
			return new Event(Kind.CLEAR, List.of());
		}

		return null;
	}

	private static Event present(String... names) {
		return present(List.of(names));
	}

	private static Event present(List<String> names) {
		List<String> real = new ArrayList<>();
		for (String name : names) {
			if (isReal(name)) {
				real.add(name);
			}
		}
		return real.isEmpty() ? null : new Event(Kind.PRESENT, real);
	}

	/** "You" is a valid username shape but is how the game talks about you, never another player. */
	private static boolean isReal(String name) {
		return PlayerNames.isValid(name) && !name.equals("You");
	}

	/** The usernames in a list such as "[MVP+] Alice, Bob, [VIP] Carol", skipping anything that is not a name. */
	private static List<String> namesIn(String list, String separator) {
		List<String> names = new ArrayList<>();
		for (String item : list.split(Pattern.quote(separator))) {
			String token = item.strip().replaceAll("^(?:\\[[^\\]]{1,24}\\]\\s*)+", "").strip();
			if (PlayerNames.isValid(token)) {
				names.add(token);
			}
		}
		return names;
	}
}
