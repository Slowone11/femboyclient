package com.femboyclient;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Settings, stored as a plain text file at config/femboyclient.properties in the
 * game directory. Edit it with any text editor; the file is created the first
 * time the mod runs.
 *
 * Values are layered, lowest priority first:
 *   1. the built-in defaults below
 *   2. femboyclient-defaults.properties baked into the jar at build time, which is
 *      how a jar copied to another PC already knows the server address and secret
 *   3. the player's own config file
 */
public final class FemboyClientConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger(FemboyClientMod.MOD_ID);

	/** The master switch: off stops every module and all talking to the website. */
	public boolean enabled = true;

	// The player scanner has no setting: it always reports the players in your lobby and what players say, in every
	// world, while "enabled" is true (see PlayerTracker and PlayerChatCollector). The old "scanner", "scannerWorld" and
	// "playerChat" lines of an older file are ignored.

	/**
	 * The Lobby Networth module: look up each lobby player on the website (their networth, rank and how often you have
	 * met them) when the Lobby Networth screen asks for one. On by default. Off just skips the website lookup; the
	 * screen still lists who is in your lobby, from the game's own tab list.
	 */
	public boolean lobbyNetworth = true;

	/** Base address of the website API. No trailing slash needed. */
	public String serverUrl = "http://127.0.0.1:8000";

	/**
	 * The website's folder (the one with app.py and .venv) on this PC, for example D:/COinView/server. When it is set
	 * and serverUrl points at this PC, the mod starts the website with the game and restarts it if it stops. Empty, or a
	 * folder that does not exist (as on another PC), means the mod never starts it.
	 */
	public String serverDir = "";

	/**
	 * The website's enrol secret. With it the mod links whichever account launched
	 * the game to the website on its own, and gets that account's API key back.
	 * Find it in the server's config.json.
	 */
	public String enrollSecret = "";

	/**
	 * Optional. A key from the server's config.json to use as it is, instead of
	 * linking the account automatically. Leave empty in normal use.
	 */
	public String apiKey = "";

	/**
	 * Where the mod updates itself from: a GitHub repository ("owner/name") whose newest release holds the jar, and a fine-grained
	 * token with read-only access to that repository's contents. Only ever read from the defaults baked into the jar, never from the
	 * player's file and never saved to it, so a jar built with a renewed token always uses the new one. Both empty (a development run,
	 * or a jar built without them) means updates come from the website, as before.
	 */
	public String updateRepo = "";
	public String updateToken = "";

	/** How often the background thread links the account and sends what the trackers queued, in seconds. */
	public int uploadIntervalSeconds = 5;

	/** MansionFinder: the Crystal Hollows scanner and its map in the corner of the screen (see HollowsHud). Placed from its screen in the G menu. */
	public boolean hud = true;

	/** While in the Crystal Hollows, ask for the biggest view distance so every chunk the server sends is scanned. Restored on leaving. */
	public boolean scanRenderBoost = true;

	/**
	 * Let the website's Live tab click, pick a hotbar slot, scroll and type in this game (see LiveInput). On by default, and it can
	 * be switched off for each account in the Live tab itself. It only ever works from the PC the website runs on, with its admin
	 * token. No in-game switch: edit this file to turn it off for good on one PC.
	 */
	public boolean liveInput = true;

	/**
	 * Auto Conversation: click the right option of a Hypixel NPC dialog for you (see NpcAutoAnswer). On by default — with one
	 * option offered it is always clicked (nothing to decide), and with two or more the green one is clicked, or nothing if
	 * none of them is green. Switch it in the G menu.
	 */
	public boolean npcAuto = true;

	/**
	 * The Frienemys module: a top-left overlay listing any player you have tagged friendly or enemy who is in your
	 * current lobby, and a G menu screen to tag lobby players and write a note on why. On by default. Off hides the
	 * overlay; the tags themselves live on the website and are unaffected.
	 */
	public boolean frienemys = true;

	/**
	 * Fullbright: forces the game's brightness to maximum, past what the options screen's own gamma slider allows,
	 * so every block renders fully lit. Off by default, since it changes how the game looks. Switch it in the G menu.
	 */
	public boolean fullbright = false;

	/**
	 * Keepsprint: keeps you sprinting while moving in any direction, not just forward like vanilla. Off by
	 * default, since it changes how movement feels. Switch it in the G menu.
	 */
	public boolean keepsprint = false;

	/**
	 * No Particles: hides every particle (block breaking, mobs, hits, explosions, potions, rain, abilities) before it is made
	 * (see NoParticles). Off by default, since it changes how the game looks. Switch it in the G menu.
	 */
	public boolean noParticles = false;

	/**
	 * HandMan: changes the size, position, rotation and swing speed of your first-person hand and held item (see HandMan).
	 * Off by default. Numbers are hundredths (degrees for yaw, pitch and roll); 0 everywhere is the game's own look.
	 */
	public boolean handMan = false;
	public int handSize = 0, handX = 0, handY = 0, handZ = 0;
	public int handYaw = 0, handPitch = 0, handRoll = 0;
	public int handSpeed = 0;
	public boolean handIgnoreHaste = false, handBlockHit = false, handNoEquipReset = false, handNoSwing = false, handNoBlock = false;

	/**
	 * The Looped Clicker module: shows click controls whenever a container is open (see
	 * {@link LoopedClickerOverlay}). Off by default. Switching it on in the G menu only makes the controls
	 * appear — the loop never starts on its own. Fields keep their original "clicker" names so a config file
	 * from before the module was renamed still loads.
	 */
	public boolean clicker = false;

	/** False clicks the left button, true the right button. */
	public boolean clickerRightClick = false;

	/** How long the loop waits between clicks, in milliseconds. */
	public int clickerMs = 500;

	/**
	 * Where on screen it clicks, in GUI pixels, set with the panel's "Set Location" button. -1 means never set
	 * yet: nothing is drawn and {@link LoopedClicker} falls back to the screen's centre if Start is pressed anyway.
	 */
	public int clickerX = -1, clickerY = -1;

	/** The key that toggles Start/Stop while a container is open, as a GLFW key code. -1 means none is set. */
	public int clickerKey = -1;

	/** Where the Looped Clicker panel itself sits. -1 means "never dragged yet": the top right corner until it is. */
	public int clickerPanelX = -1, clickerPanelY = -1;

	/** Where the map's top-left corner is, in GUI pixels from the top-left of the screen. */
	public int hudX = 4, hudY = 4;

	/** How wide and tall the map itself is, in GUI pixels. */
	public int hudSize = 150;

	private static FemboyClientConfig instance;

	/** The one shared config, loaded on first use. Safe to call from any thread. */
	public static synchronized FemboyClientConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("femboyclient.properties");
	}

	/** Reads the config file, creating it if it does not exist. */
	private static FemboyClientConfig load() {
		Properties bundled = readBundledDefaults();
		Properties user = new Properties();

		Path path = file();
		boolean fileRead = false;
		if (Files.exists(path)) {
			try (InputStream in = Files.newInputStream(path)) {
				user.load(in);
				fileRead = true;
			} catch (IOException e) {
				LOGGER.warn("Could not read {}, using defaults", path, e);
			}
		}

		FemboyClientConfig config = fromProperties(bundled, user);

		// Create the file, or add any setting this version introduced, so every
		// option is visible in the file rather than hidden.
		// (the keys a file must have are exactly the ones save() writes)
		if (!fileRead || !user.stringPropertyNames().containsAll(config.toProperties().stringPropertyNames())) {
			config.save();
		}

		LOGGER.info("config: enabled={} serverUrl={} serverDir={} enrollSecret={} apiKey={} updates={}",
				config.enabled, config.serverUrl,
				config.serverDir.isEmpty() ? "(not set)" : config.serverDir,
				config.enrollSecret.isBlank() ? "(not set)" : "(set)",
				config.apiKey.isBlank() ? "(not set)" : "(set)",
				config.updatesFromGitHub() ? "GitHub " + config.updateRepo : "website");
		return config;
	}

	/** Builds a config from the two layers. Package-private so it can be tested. */
	static FemboyClientConfig fromProperties(Properties bundled, Properties user) {
		FemboyClientConfig config = new FemboyClientConfig();
		config.apply(bundled);
		config.bundledUrls = urls(bundled);
		config.apply(user);
		config.updateRepo = text(bundled, "updateRepo", "", true);       // the jar's own, whatever the file says (see updateRepo)
		config.updateToken = text(bundled, "updateToken", "", true);
		return config;
	}

	/** True when the jar was built to update itself from GitHub releases instead of the website. */
	public boolean updatesFromGitHub() {
		return !updateRepo.isBlank() && !updateToken.isBlank();
	}

	/** The website addresses the jar was built with: serverUrl, then every address in serverUrls (comma separated). */
	private List<String> bundledUrls = List.of();

	private static List<String> urls(Properties props) {
		List<String> out = new java.util.ArrayList<>();
		String first = props.getProperty("serverUrl");
		if (first != null && !first.isBlank()) {
			out.add(first.trim());
		}
		String more = props.getProperty("serverUrls");
		if (more != null) {
			for (String one : more.split("[,;\\s]+")) {
				if (!one.isBlank()) {
					out.add(one.trim());
				}
			}
		}
		return List.copyOf(out);
	}

	/**
	 * Every address the website may be at, best guess first: the one in the config file, then the ones the jar was built with. The
	 * mod tries them in this order and keeps the one that answers, so a jar copied to another PC still finds the website when an old
	 * config file there names an address that PC cannot reach (a home network address, or 127.0.0.1).
	 */
	public List<String> serverUrlCandidates() {
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		out.add(serverUrl.trim().replaceAll("/+$", ""));
		for (String url : bundledUrls) {
			out.add(url.trim().replaceAll("/+$", ""));
		}
		out.removeIf(String::isBlank);
		return List.copyOf(out);
	}

	/** Copies over every value present in the properties, ignoring bad ones. */
	private void apply(Properties props) {
		enabled = bool(props, "enabled", enabled);
		lobbyNetworth = bool(props, "lobbyNetworth", lobbyNetworth);
		serverUrl = text(props, "serverUrl", serverUrl, false);
		serverDir = text(props, "serverDir", serverDir, true);
		enrollSecret = text(props, "enrollSecret", enrollSecret, false);      // a blank line in an old file must not wipe the built-in one
		apiKey = text(props, "apiKey", apiKey, true);
		uploadIntervalSeconds = integer(props, "uploadIntervalSeconds", uploadIntervalSeconds);
		hud = bool(props, "hud", hud);
		scanRenderBoost = bool(props, "scanRenderBoost", scanRenderBoost);
		liveInput = bool(props, "liveInput", liveInput);
		npcAuto = bool(props, "npcAuto", npcAuto);
		frienemys = bool(props, "frienemys", frienemys);
		fullbright = bool(props, "fullbright", fullbright);
		keepsprint = bool(props, "keepsprint", keepsprint);
		noParticles = bool(props, "noParticles", noParticles);
		handMan = bool(props, "handMan", handMan);
		handSize = HandMan.SIZE.clamp(integer(props, "handSize", handSize));
		handX = HandMan.X.clamp(integer(props, "handX", handX));
		handY = HandMan.Y.clamp(integer(props, "handY", handY));
		handZ = HandMan.Z.clamp(integer(props, "handZ", handZ));
		handYaw = HandMan.ROTATION.clamp(integer(props, "handYaw", handYaw));
		handPitch = HandMan.ROTATION.clamp(integer(props, "handPitch", handPitch));
		handRoll = HandMan.ROTATION.clamp(integer(props, "handRoll", handRoll));
		handSpeed = HandMan.SPEED.clamp(integer(props, "handSpeed", handSpeed));
		handIgnoreHaste = bool(props, "handIgnoreHaste", handIgnoreHaste);
		handBlockHit = bool(props, "handBlockHit", handBlockHit);
		handNoEquipReset = bool(props, "handNoEquipReset", handNoEquipReset);
		handNoSwing = bool(props, "handNoSwing", handNoSwing);
		handNoBlock = bool(props, "handNoBlock", handNoBlock);
		clicker = bool(props, "clicker", clicker);
		clickerRightClick = bool(props, "clickerRightClick", clickerRightClick);
		clickerMs = Math.max(LoopedClicker.MIN_MS, Math.min(LoopedClicker.MAX_MS, integer(props, "clickerMs", clickerMs)));
		clickerX = integer(props, "clickerX", clickerX);
		clickerY = integer(props, "clickerY", clickerY);
		clickerKey = integer(props, "clickerKey", clickerKey);
		clickerPanelX = integer(props, "clickerPanelX", clickerPanelX);
		clickerPanelY = integer(props, "clickerPanelY", clickerPanelY);
		hudX = Math.max(0, integer(props, "hudX", hudX));
		hudY = Math.max(0, integer(props, "hudY", hudY));
		hudSize = Math.max(HollowsHud.MIN_SIZE, Math.min(HollowsHud.MAX_SIZE, integer(props, "hudSize", hudSize)));	}

	/** Defaults built into the jar, or empty if this build has none. */
	private static Properties readBundledDefaults() {
		Properties props = new Properties();
		try (InputStream in = FemboyClientConfig.class.getResourceAsStream("/femboyclient-defaults.properties")) {
			if (in != null) {
				props.load(in);
			}
		} catch (IOException e) {
			LOGGER.warn("Could not read the built-in defaults", e);
		}
		return props;
	}

	/** Every setting as the text the file holds. Both save() and the "is the file missing a key" check use this. Package-private for tests. */
	Properties toProperties() {
		Properties props = new Properties();
		props.setProperty("enabled", Boolean.toString(enabled));
		props.setProperty("lobbyNetworth", Boolean.toString(lobbyNetworth));
		props.setProperty("serverUrl", serverUrl);
		props.setProperty("serverDir", serverDir);
		props.setProperty("enrollSecret", enrollSecret);
		props.setProperty("apiKey", apiKey);
		props.setProperty("uploadIntervalSeconds", Integer.toString(uploadIntervalSeconds));
		props.setProperty("hud", Boolean.toString(hud));
		props.setProperty("scanRenderBoost", Boolean.toString(scanRenderBoost));
		props.setProperty("liveInput", Boolean.toString(liveInput));
		props.setProperty("npcAuto", Boolean.toString(npcAuto));
		props.setProperty("frienemys", Boolean.toString(frienemys));
		props.setProperty("fullbright", Boolean.toString(fullbright));
		props.setProperty("keepsprint", Boolean.toString(keepsprint));
		props.setProperty("noParticles", Boolean.toString(noParticles));
		props.setProperty("handMan", Boolean.toString(handMan));
		props.setProperty("handSize", Integer.toString(handSize));
		props.setProperty("handX", Integer.toString(handX));
		props.setProperty("handY", Integer.toString(handY));
		props.setProperty("handZ", Integer.toString(handZ));
		props.setProperty("handYaw", Integer.toString(handYaw));
		props.setProperty("handPitch", Integer.toString(handPitch));
		props.setProperty("handRoll", Integer.toString(handRoll));
		props.setProperty("handSpeed", Integer.toString(handSpeed));
		props.setProperty("handIgnoreHaste", Boolean.toString(handIgnoreHaste));
		props.setProperty("handBlockHit", Boolean.toString(handBlockHit));
		props.setProperty("handNoEquipReset", Boolean.toString(handNoEquipReset));
		props.setProperty("handNoSwing", Boolean.toString(handNoSwing));
		props.setProperty("handNoBlock", Boolean.toString(handNoBlock));
		props.setProperty("clicker", Boolean.toString(clicker));
		props.setProperty("clickerRightClick", Boolean.toString(clickerRightClick));
		props.setProperty("clickerMs", Integer.toString(clickerMs));
		props.setProperty("clickerX", Integer.toString(clickerX));
		props.setProperty("clickerY", Integer.toString(clickerY));
		props.setProperty("clickerKey", Integer.toString(clickerKey));
		props.setProperty("clickerPanelX", Integer.toString(clickerPanelX));
		props.setProperty("clickerPanelY", Integer.toString(clickerPanelY));
		props.setProperty("hudX", Integer.toString(hudX));
		props.setProperty("hudY", Integer.toString(hudY));
		props.setProperty("hudSize", Integer.toString(hudSize));		return props;
	}

	/** Writes the current values back to disk. A temp file is swapped in, so a crash cannot leave half a file. */
	public void save() {
		Properties props = toProperties();
		Path path = file();
		try {
			Files.createDirectories(path.getParent());
			Path temp = path.resolveSibling(path.getFileName() + ".tmp");
			try (OutputStream out = Files.newOutputStream(temp)) {
				props.store(out, "FemboyClient settings. enrollSecret is a password: do not share this file.");
			}
			try {
				Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.warn("Could not write {}", path, e);
		}
	}

	/**
	 * @param allowBlank whether an empty value is meaningful. For the address it is
	 *                   not, so a blank one keeps the previous value instead.
	 */
	private static String text(Properties props, String key, String fallback, boolean allowBlank) {
		String value = props.getProperty(key);
		if (value == null) {
			return fallback;
		}
		value = value.trim();
		return value.isEmpty() && !allowBlank ? fallback : value;
	}

	private static int integer(Properties props, String key, int fallback) {
		String value = props.getProperty(key);
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static boolean bool(Properties props, String key, boolean fallback) {
		String value = props.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value.trim());
	}
}
