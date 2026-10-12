package com.femboyclient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Finds where the Crystal Hollows structures are in your lobby.
 *
 * Nothing here reads blocks. The game already tells you which area you are in (the sidebar and the tab list
 * say "Mines of Divan", "Jungle Temple" and so on), so while that area is a structure this reports where
 * you are standing. The website joins the positions into a box, and the middle of the box is the structure.
 *
 * Two accounts in the same lobby need to find each other, so the lobby is named by a salted hash of the
 * server code on the sidebar. The code itself is never sent, stored or logged. The salt is the enrol secret,
 * so all your PCs make the same hash and nobody else can.
 *
 * Time is counted in client ticks (20 a second) and the game is reached through a supplier, so all of this
 * is testable without Minecraft.
 */
final class HollowsTracker {

	/** The world name the scanner gives the Crystal Hollows. */
	static final String WORLD = "Crystal Hollows";

	/** The areas that are a structure, as the website names them. */
	static final List<String> STRUCTURES = List.of("Jungle Temple", "Mines of Divan", "Goblin Queen's Den",
			"Lost Precursor City", "Khazad-dûm", "Fairy Grotto", "Dragon's Lair", "Odawa");

	/** What the game shows right now. */
	record Reading(Object level, String world, String area, String lobbyCode, String date, int x, int y, int z) {
	}

	/** Where a position goes. {@code structure} is empty for "I am in this lobby". */
	interface Sink {
		void send(String lobby, String structure, int x, int y, int z, Instant at, boolean scan);
	}

	/** Where a structure recognised from its blocks goes: its box, and for a Fairy Grotto its jasper count and rare block kinds. */
	interface BoxSink {
		void send(String lobby, String structure, int[] box, int jasper, java.util.Collection<String> kinds, Instant at);
	}

	static final int POLL_TICKS = 20;                 // the game is read once a second
	static final int SETTLE_TICKS = 100;              // after the world changes, wait this long: the sidebar still shows the old server code at first
	static final int SETTLE_POLL_TICKS = 4;           // while waiting for the sidebar after a hop, look this often
	static final int PRESENCE_TICKS = 20 * 5;         // "still here" is said every 5 seconds, and at once after walking this far,
	static final int PRESENCE_MOVE = 6;               // so the live dot on the page follows you
	static final int MOVE_BLOCKS = 6;                 // a new position in the same structure is sent after this much walking
	static final int REPEAT_TICKS = 20 * 30;          // or after this long, so a structure's last seen time stays fresh

	private static final Map<String, String> BY_NAME = new HashMap<>();

	static {
		for (String name : STRUCTURES) {
			BY_NAME.put(SkyBlockWorlds.normalise(name), name);
		}
	}

	private final Sink sink;
	private final BoxSink boxSink;
	private final Supplier<String> salt;

	private Object level;                             // the game world we are in; a new one means a hop or a warp
	private long settledAt;                           // no reports before this tick
	private String lobby;                             // the hash of the current lobby, null when not in the Hollows
	private volatile String date;                     // the SkyBlock date on the sidebar ("Early Autumn 12th"): shown, and sent to the website
	private volatile String code;                    // the server code itself ("m174D"), only for showing on the map: it is never sent anywhere
	private String leftCode;                          // the code of the lobby just hopped away from, so a different one on the sidebar is known to be the new lobby
	private int wait;
	private long tick;
	private long presenceAt;
	private String lastStructure;
	private int lastX, lastY, lastZ;
	private long lastSentAt;
	private volatile int playerX, playerZ;

	HollowsTracker(Sink sink, BoxSink boxSink, Supplier<String> salt) {
		this.sink = sink;
		this.boxSink = boxSink;
		this.salt = salt;
	}

	/** The website's name for an area, or null when it is not a structure. */
	static String structureFor(String area) {
		return area == null ? null : BY_NAME.get(SkyBlockWorlds.normalise(area));
	}

	private static final java.util.regex.Pattern LOBBY_CODE_LINE =
			java.util.regex.Pattern.compile("^\\d{2}/\\d{2}/\\d{2}\\s+([A-Za-z0-9]{2,12})\\b.*$");
	private static final java.util.regex.Pattern SKYBLOCK_DATE_LINE =
			java.util.regex.Pattern.compile("^((?:Early |Late )?(?:Spring|Summer|Autumn|Winter) \\d{1,2}(?:st|nd|rd|th))$");

	/**
	 * The server code on the sidebar's date line ("09/26/26 m18AK"), or null. Only ever used to make the hash.
	 */
	static String lobbyCode(List<String> sidebarLines) {
		for (String text : sidebarLines) {
			java.util.regex.Matcher match = LOBBY_CODE_LINE.matcher(ScoreboardReader.strip(text));
			if (match.matches()) {
				return match.group(1);
			}
		}
		return null;
	}

	/** The SkyBlock date line of the sidebar ("Early Autumn 12th", "Late Summer 3rd"), or null. */
	static String skyblockDate(List<String> sidebarLines) {
		for (String text : sidebarLines) {
			java.util.regex.Matcher match = SKYBLOCK_DATE_LINE.matcher(ScoreboardReader.strip(text).trim());
			if (match.matches()) {
				return match.group(1);
			}
		}
		return null;
	}

	/** The lobby name the website sees: the salted hash, 32 hex characters. Never reversible without the salt. */
	static String lobbyKey(String salt, String code) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(("femboyclient-hollows|" + salt + "|" + code.toLowerCase(Locale.ROOT))
					.getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(hash, 0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);   // SHA-256 is always there
		}
	}

	/**
	 * Call every client tick.
	 *
	 * @param reading reads the game, or returns null when there is no world. Only called when a read is due.
	 */
	void tick(Supplier<Reading> reading, Instant now) {
		tick++;
		if (--wait > 0) {
			return;
		}
		wait = POLL_TICKS;

		Reading read = reading.get();
		String secret = salt.get();
		if (read == null || !WORLD.equals(read.world())) {
			reset();
			return;
		}

		if (read.level() != level) {
			// A new world is a new lobby, whatever the sidebar says for the first few seconds. Positions sent now would
			// be filed under the lobby you just left, which is how boxes grow across lobbies when hopping.
			String hoppedFrom = level != null ? code : null;   // only a straight hop between lobbies: from anywhere else the sidebar may show any code
			reset();
			level = read.level();
			settledAt = tick + SETTLE_TICKS;
			leftCode = hoppedFrom;
		}
		if (tick < settledAt && leftCode != null) {
			// The sidebar shows the lobby you left until it updates. As soon as it shows another code, that is the new lobby.
			String shown = read.lobbyCode();
			if (shown != null && !shown.equals(leftCode)) {
				settledAt = tick;
			} else {
				wait = SETTLE_POLL_TICKS;
			}
		}
		// The world is known from now on, so things can already be scanned and drawn. The lobby is only named once the sidebar can be
		// trusted; what is found before that waits in {@link #pending} and goes out then.
		if (tick < settledAt) {
			lobby = null;
			code = null;                                  // the sidebar still shows the lobby you left
			date = null;
			return;
		}
		code = read.lobbyCode();
		date = read.date();
		if (read.lobbyCode() == null || secret == null || secret.isBlank()) {
			lobby = null;
			return;
		}

		String key = lobbyKey(secret, read.lobbyCode());
		if (!key.equals(lobby)) {
			lobby = key;
			lastStructure = null;
			presenceAt = 0;
			for (Pending waiting : pending.values()) {
				boxSink.send(lobby, waiting.structure(), waiting.box(), waiting.jasper(), waiting.kinds(), now);
			}
			pending.clear();
		}
		boolean walked = Math.abs(read.x() - playerX) >= PRESENCE_MOVE || Math.abs(read.z() - playerZ) >= PRESENCE_MOVE;
		if (presenceAt == 0 || walked || tick - presenceAt >= PRESENCE_TICKS) {
			presenceAt = tick;
			sink.send(lobby, "", read.x(), read.y(), read.z(), now, false);
		}
		playerX = read.x();
		playerZ = read.z();

		String structure = structureFor(read.area());
		if (structure == null) {
			lastStructure = null;
			return;
		}

		boolean moved = Math.abs(read.x() - lastX) >= MOVE_BLOCKS || Math.abs(read.y() - lastY) >= MOVE_BLOCKS
				|| Math.abs(read.z() - lastZ) >= MOVE_BLOCKS;
		if (!structure.equals(lastStructure) || moved || tick - lastSentAt >= REPEAT_TICKS) {
			lastStructure = structure;
			lastX = read.x();
			lastY = read.y();
			lastZ = read.z();
			lastSentAt = tick;
			sink.send(lobby, structure, read.x(), read.y(), read.z(), now, false);
		}
	}

	/**
	 * The game world the lobby was named for. The tracker only reads the game once a second, so for up to a second after a hop it
	 * still holds the old lobby: anything that reports to the lobby must check that this is still the world it is looking at.
	 */
	Object level() {
		return level;
	}

	/**
	 * The server code on the sidebar ("m174D"), or null while it cannot be trusted yet (the first seconds after a hop) or is not
	 * there. It is for showing on the map only; what leaves the game is the salted hash, never this.
	 */
	String lobbyCode() {
		return code;
	}

	/** The SkyBlock date of this lobby ("Early Autumn 12th"), or null while it cannot be trusted yet. */
	String date() {
		return date;
	}

	/** True while the last reading was in the Crystal Hollows, whether or not the lobby has been named yet. */
	boolean inHollowsWorld() {
		return level != null;
	}

	/** True while the last reading was in the Crystal Hollows with a known lobby. */
	boolean inLobby() {
		return lobby != null;
	}

	/** The salted name of this lobby, as the website knows it, or null while it cannot be trusted yet. */
	String lobbyKey() {
		return lobby;
	}

	/** The structure the game said the player was in at the last reading, or null. */
	String currentStructure() {
		return lastStructure;
	}

	int playerX() {
		return playerX;
	}

	int playerZ() {
		return playerZ;
	}

	/** A structure recognised from its blocks that is waiting for the lobby to be named. Only the latest of each is kept. */
	private record Pending(String structure, int[] box, int jasper, java.util.Collection<String> kinds) {
	}

	private final java.util.Map<Integer, Pending> pending = new java.util.LinkedHashMap<>();

	/**
	 * A structure recognised from its blocks, marked as coming from a scan. {@code id} stays the same while the structure grows.
	 * Right after a hop the lobby is not named yet: the find waits and goes out the moment it is.
	 */
	void reportScan(String structure, int id, int[] box, int jasper, java.util.Collection<String> kinds, Instant now) {
		if (level == null || structure == null || box == null) {
			return;
		}
		if (lobby == null) {
			pending.put(id, new Pending(structure, box, jasper, java.util.Set.copyOf(kinds)));
			return;
		}
		boxSink.send(lobby, structure, box, jasper, kinds, now);
	}

	/** Forgets the lobby, e.g. on disconnect or on leaving the Hollows. The next Hollows starts fresh. */
	void reset() {
		pending.clear();
		code = null;
		date = null;
		level = null;
		lobby = null;
		lastStructure = null;
		presenceAt = 0;
	}
}
