package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The player scanner: reports who is in your lobby, and which SkyBlock world it is.
 *
 * It only reads what the game already has (the tab list and the sidebar). Nothing is
 * sent to the game server. What goes to your own website is usernames, times and the
 * SkyBlock world name, nothing else. It does nothing until you switch it on in the
 * G menu's Scanner module.
 *
 * Each time the game replaces its world (login or respawn, which is what a lobby
 * change is), that starts a new visit. LobbyWatcher does the timing and works out
 * which names are new; this class connects it to Minecraft and to ServerLink.
 */
public final class PlayerTracker {

	private static final Logger LOGGER = LoggerFactory.getLogger(FemboyClientMod.MOD_ID);

	/** How many names to show in the log line. The full list can be long. */
	private static final int LOGGED_NAMES = 10;

	private final LobbyWatcher watcher = new LobbyWatcher(this::report,
			world -> FemboyClientConfig.get().scannerAllows(world));

	private ClientLevel lastLevel;
	private boolean warned;

	// What the last reads found, for the log lines.
	private int lastEntries, lastPlayers;
	private java.util.Map<Integer, Integer> lastVersions = java.util.Map.of();
	private List<String> lastUnrecognised = List.of();
	private ClientLevel worldLoggedFor;
	private volatile String knownWorld;

	/** Called every client tick. Never throws. */
	public void onClientTick(Minecraft mc) {
		try {
			FemboyClientConfig config = FemboyClientConfig.get();
			if (!config.enabled || !config.scanner) {
				watcher.reset();
				lastLevel = null;
				return;
			}

			ClientLevel level = mc.level;
			if (level != null && level != lastLevel) {
				LOGGER.info("player tracker: the world changed, watching for a new lobby");
				knownWorld = null;
			}
			lastLevel = level;

			watcher.tick(level, () -> readWorld(mc), () -> readPlayers(mc), Instant.now());
		} catch (RuntimeException e) {
			// Never let the tracker disturb the game. Say so once, not every tick.
			if (!warned) {
				warned = true;
				LOGGER.warn("player tracker failed, it will keep trying", e);
			}
		}
	}

	/** Called when the client disconnects from a server. */
	public void onDisconnect() {
		watcher.reset();
		lastLevel = null;
		worldLoggedFor = null;
		knownWorld = null;
	}

	/** The world the scanner last found for this lobby, or null if none yet. Cheap: nothing is read. */
	public String knownWorld() {
		return knownWorld;
	}

	/** The lower-case usernames of the real players in your tab list now, without you. Client thread only. */
	public java.util.Set<String> lobbyNames() {
		try {
			Minecraft mc = Minecraft.getInstance();
			java.util.Set<String> names = new java.util.HashSet<>();
			String inWorld = mc.player == null ? null : mc.player.getName().getString();
			for (String name : TabList.players(tabEntries(mc), Account.current().name(), inWorld)) {
				names.add(name.toLowerCase(java.util.Locale.ROOT));
			}
			return names;
		} catch (RuntimeException e) {
			return java.util.Set.of();
		}
	}

	/** The real players in your tab list now, with their UUIDs, without you, sorted by name. Client thread only. */
	public List<LobbyPlayer> lobbyPlayers() {
		try {
			Minecraft mc = Minecraft.getInstance();
			String inWorld = mc.player == null ? null : mc.player.getName().getString();
			return TabList.lobbyPlayers(tabEntries(mc), Account.current().name(), inWorld);
		} catch (RuntimeException e) {
			return List.of();
		}
	}

	/**
	 * What the Crystal Hollows map needs from the game: world, area, the server code on the sidebar and where you stand.
	 * Null when there is no world. Client thread only. The server code goes no further than HollowsTracker's hash.
	 */
	HollowsTracker.Reading hollowsReading(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return null;
		}
		WorldDetector.Detection found = detect(mc);
		ScoreboardReader.Sidebar sidebar = ScoreboardReader.read(mc);
		String code = sidebar == null ? null : HollowsTracker.lobbyCode(sidebar.lines());
		String date = sidebar == null ? null : HollowsTracker.skyblockDate(sidebar.lines());
		net.minecraft.core.BlockPos at = mc.player.blockPosition();
		return new HollowsTracker.Reading(mc.level, found.world(), found.area(), code, date, at.getX(), at.getY(), at.getZ());
	}

	/** The SkyBlock world you are in right now, or null if it cannot be told. For the Scanner screen. */
	public String currentWorld() {
		try {
			return detect(Minecraft.getInstance()).world();
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- reading the game

	/** Every tab list entry with the text the game shows for it. */
	private static List<TabList.Entry> tabEntries(Minecraft mc) {
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			return List.of();
		}

		List<TabList.Entry> entries = new ArrayList<>();
		for (PlayerInfo info : connection.getListedOnlinePlayers()) {
			entries.add(new TabList.Entry(info.getProfile().name(), shownText(info), info.getProfile().id().version(),
					info.getProfile().id()));
		}
		return entries;
	}

	/**
	 * What the tab list displays for an entry. A custom display name wins. Otherwise
	 * it is the plain name with any team prefix and suffix, which is how the game
	 * itself draws it.
	 */
	private static String shownText(PlayerInfo info) {
		Component custom = info.getTabListDisplayName();
		if (custom != null) {
			return custom.getString();
		}

		Component name = Component.literal(info.getProfile().name());
		PlayerTeam team = info.getTeam();
		return (team == null ? name : PlayerTeam.formatNameForTeam(team, name)).getString();
	}

	/** The real players in the tab list, without your own name. */
	private List<String> readPlayers(Minecraft mc) {
		List<TabList.Entry> entries = tabEntries(mc);
		String inWorld = mc.player == null ? null : mc.player.getName().getString();
		List<String> players = TabList.players(entries, Account.current().name(), inWorld);

		lastEntries = entries.size();
		lastPlayers = players.size();
		lastVersions = TabList.versionCounts(entries);
		return players;
	}

	private WorldDetector.Detection detect(Minecraft mc) {
		List<String> sidebar = List.of();
		ScoreboardReader.Sidebar read = ScoreboardReader.read(mc);
		if (read != null && read.title().toUpperCase(Locale.ROOT).contains("SKYBLOCK")) {
			sidebar = read.lines();
		}

		WorldDetector.Detection found = WorldDetector.detect(sidebar, TabList.shownTexts(tabEntries(mc)));
		lastUnrecognised = found.world() == null ? WorldDetector.unrecognised(sidebar) : List.of();
		return found;
	}

	/** The SkyBlock world, or null while it cannot be told yet. Logs what it found. */
	private String readWorld(Minecraft mc) {
		WorldDetector.Detection found = detect(mc);
		if (found.world() != null) {
			knownWorld = found.world();
		}
		if (found.world() != null && worldLoggedFor != mc.level) {
			worldLoggedFor = mc.level;
			LOGGER.info("scanner: world is {} (area \"{}\"{})", found.world(), found.area(),
					found.known() ? "" : ", not in the world table, so the area name is used");
		}
		return found.world();
	}

	// ------------------------------------------------------------------ reporting

	/** Builds the messages for the website and hands them to ServerLink. */
	private void report(List<String> players, Instant visitStarted, String world) {
		String me = Account.current().name();
		Instant now = Instant.now();

		if (SkyBlockWorlds.UNKNOWN.equals(world) && !lastUnrecognised.isEmpty()) {
			// Help correct the table: these lines might be an area name that is not in it.
			LOGGER.info("scanner: could not tell the world. Sidebar text that might be the area: {}", lastUnrecognised);
		}

		// A lobby can hold more names than one request allows, so send in chunks.
		for (int from = 0; from < players.size(); from += TrackerPayload.MAX_PLAYERS) {
			List<String> chunk = players.subList(from, Math.min(players.size(), from + TrackerPayload.MAX_PLAYERS));
			LOGGER.info("player tracker: {} player(s) in {} to report (tab list: {} entries, {} are players, UUID versions {}): {}",
					chunk.size(), world, lastEntries, lastPlayers, lastVersions, preview(chunk));
			FemboyClientMod.SERVER.enqueueTracker(TrackerPayload.build(me, world, chunk, now, visitStarted));
		}
	}

	private static String preview(List<String> names) {
		if (names.size() <= LOGGED_NAMES) {
			return names.toString();
		}
		return names.subList(0, LOGGED_NAMES) + " and " + (names.size() - LOGGED_NAMES) + " more";
	}
}
