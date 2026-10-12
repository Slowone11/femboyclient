package com.femboyclient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who is in your party right now, as far as the chat has told us.
 *
 * A player counts as a new party member each time they become confirmed after not
 * being one: joining, or being named in the party list on joining. Speaking in
 * party chat when they are already known changes nothing. If they leave and come
 * back, that is a second time.
 *
 * Kept free of Minecraft classes so it can be tested on its own.
 */
public final class PartyState {

	/** A party cannot really be this big. The cap keeps memory bounded whatever arrives. */
	static final int MAX_MEMBERS = 500;

	/** Lower-case name to the spelling last seen, in the order they were confirmed. */
	private final Map<String, String> members = new LinkedHashMap<>();

	/**
	 * Applies what one chat line said.
	 *
	 * @param own your own names, which are never counted as a party member
	 * @return the players who just became confirmed members, in order
	 */
	public List<String> apply(PartyChat.Event event, String... own) {
		List<String> added = new ArrayList<>();
		if (event == null) {
			return added;
		}

		switch (event.kind()) {
			case PRESENT -> {
				for (String name : event.names()) {
					String key = name.toLowerCase(Locale.ROOT);
					if (isOwn(key, own) || members.containsKey(key) || members.size() >= MAX_MEMBERS) {
						continue;
					}
					members.put(key, name);
					added.add(name);
				}
			}
			case LEFT -> event.names().forEach(name -> members.remove(name.toLowerCase(Locale.ROOT)));
			case CLEAR -> members.clear();
		}
		return added;
	}

	/** Forgets everyone, for example when you leave the server. */
	public void clear() {
		members.clear();
	}

	/** The members now, in the order they were confirmed. */
	public List<String> members() {
		return new ArrayList<>(members.values());
	}

	private static boolean isOwn(String key, String[] own) {
		for (String name : own) {
			if (name != null && name.toLowerCase(Locale.ROOT).equals(key)) {
				return true;
			}
		}
		return false;
	}
}
