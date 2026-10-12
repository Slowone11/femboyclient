package com.femboyclient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Notices when you enter a lobby and works out which players to report.
 *
 * Each time the game replaces its world (a new lobby, a warp, a game starting)
 * that is one visit. A few seconds later, once the tab list has filled and the
 * SkyBlock world is known, the whole list is reported. After that only players who
 * newly appear are reported. Players who leave are forgotten locally but not
 * reported, because the website only records who you met in each visit.
 *
 * A visit is identified by the moment it started, never by any server or lobby id,
 * and it is tagged with the SkyBlock world name, for example "Hub".
 *
 * Nothing is reported while the scanner setting does not allow the visit's world.
 *
 * Time is counted in client ticks (20 a second), and the game world is passed in
 * as an opaque object compared by identity, so all of this is testable without
 * Minecraft.
 */
final class LobbyWatcher {

	/** Where the result goes. */
	interface Sink {
		/**
		 * @param players      cleaned usernames to report, in this call only the new ones
		 * @param visitStarted when this visit began
		 * @param world        the SkyBlock world name, or "Unknown"
		 */
		void send(List<String> players, Instant visitStarted, String world);
	}

	/** The tab list is read once a second. */
	static final int POLL_TICKS = 20;

	/** After entering a lobby, wait this long for the tab list to fill. */
	static final int SETTLE_TICKS = 60;

	/**
	 * The sidebar can arrive a little after the tab list. If the world is still not
	 * readable this long after entering, the visit is called "Unknown" and goes ahead.
	 */
	static final int WORLD_GIVE_UP_TICKS = 100;

	/**
	 * Some events replace the world without changing lobby, for example dying and
	 * respawning. If the tab list is almost identical to the previous visit's, and it
	 * is the same SkyBlock world, this is treated as the same visit instead of
	 * starting a new one, so nobody is counted as "met again" a moment after being met.
	 */
	static final int SAME_LOBBY_MIN_PLAYERS = 8;
	static final double SAME_LOBBY_OVERLAP = 0.8;

	private final Sink sink;
	private final Predicate<String> allowed;

	private Object world;                      // identity of the current world, null when not in one
	private Instant visitStarted;
	private String visitWorld;                 // the SkyBlock world, null until it has been read
	private int ticksInVisit;
	private int wait;                          // ticks until the next read
	private boolean firstRead;                 // true until this visit's first non-empty read
	private Set<String> known = new HashSet<>();   // lower-case names already reported this visit
	private Set<String> previousKnown = Set.of();  // the previous visit, only kept across a direct world swap
	private Instant previousStarted;
	private String previousWorld;

	/**
	 * @param allowed whether the scanner setting permits reporting from a given SkyBlock
	 *                world. Asked at every read, so changing the setting takes effect at once.
	 */
	LobbyWatcher(Sink sink, Predicate<String> allowed) {
		this.sink = sink;
		this.allowed = allowed;
	}

	/**
	 * Call every client tick.
	 *
	 * @param currentWorld the game's current world, or null when not in a world
	 * @param worldName    reads the SkyBlock world name, or null while it cannot be read yet.
	 *                     Only called when a read is due.
	 * @param names        reads the cleaned tab list. Only called when a read is due.
	 * @param now          the current time
	 */
	void tick(Object currentWorld, Supplier<String> worldName, Supplier<List<String>> names, Instant now) {
		if (currentWorld == null) {
			reset();
			return;
		}

		if (currentWorld != world) {
			enter(currentWorld, now);
		}

		ticksInVisit++;
		if (--wait > 0) {
			return;
		}
		wait = POLL_TICKS;
		poll(worldName, names);
	}

	/** Forgets everything, e.g. on disconnect. The next world entered starts a fresh visit. */
	void reset() {
		world = null;
		visitStarted = null;
		visitWorld = null;
		known = new HashSet<>();
		previousKnown = Set.of();
		previousStarted = null;
		previousWorld = null;
	}

	private void enter(Object newWorld, Instant now) {
		// A direct swap remembers the last visit for the same-lobby check.
		// Leaving the world entirely does not, because reset() cleared it.
		if (world != null && !known.isEmpty() && visitWorld != null) {
			previousKnown = known;
			previousStarted = visitStarted;
			previousWorld = visitWorld;
		} else {
			previousKnown = Set.of();
			previousStarted = null;
			previousWorld = null;
		}

		world = newWorld;
		visitStarted = now;
		visitWorld = null;
		ticksInVisit = 0;
		known = new HashSet<>();
		firstRead = true;
		wait = SETTLE_TICKS;
	}

	private void poll(Supplier<String> worldName, Supplier<List<String>> names) {
		// Which SkyBlock world is this? Wait for the sidebar if it has not appeared yet.
		if (visitWorld == null) {
			String detected = worldName.get();
			if (detected != null) {
				visitWorld = detected;
			} else if (ticksInVisit >= WORLD_GIVE_UP_TICKS) {
				visitWorld = SkyBlockWorlds.UNKNOWN;
			} else {
				return;
			}
		}

		if (!allowed.test(visitWorld)) {
			// The scanner is set to another world. Say nothing, and keep nothing, so if the
			// setting changes later everyone in the lobby is reported then.
			known = new HashSet<>();
			return;
		}

		List<String> current = names.get();
		if (current.isEmpty()) {
			// An empty lobby, or the list has not filled yet. Nothing to say, and we
			// keep looking every second, so this doubles as the retry.
			known = new HashSet<>();
			return;
		}

		if (firstRead) {
			firstRead = false;
			if (isSameLobbyAsBefore(current)) {
				visitStarted = previousStarted;
				known = new HashSet<>(previousKnown);
			}
		}

		List<String> fresh = new ArrayList<>();
		Set<String> now = new HashSet<>();
		for (String name : current) {
			String key = name.toLowerCase(Locale.ROOT);
			now.add(key);
			if (!known.contains(key)) {
				fresh.add(name);
			}
		}
		known = now;   // anyone who left drops out, so someone who comes back is reported again

		if (!fresh.isEmpty()) {
			sink.send(fresh, visitStarted, visitWorld);
		}
	}

	private boolean isSameLobbyAsBefore(List<String> current) {
		if (previousKnown.size() < SAME_LOBBY_MIN_PLAYERS || !visitWorld.equals(previousWorld)) {
			return false;
		}
		int shared = 0;
		for (String name : current) {
			if (previousKnown.contains(name.toLowerCase(Locale.ROOT))) {
				shared++;
			}
		}
		int smaller = Math.min(current.size(), previousKnown.size());
		return shared >= SAME_LOBBY_OVERLAP * smaller;
	}
}
