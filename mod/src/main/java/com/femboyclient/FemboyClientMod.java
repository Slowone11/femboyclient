package com.femboyclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entry point for the client-side half of FemboyClient.
 *
 * Mostly passive: it reads data the client already has (sidebar, tab list, chat) and talks to your own
 * website. It only sends commands, chat or clicks to the game for a feature that is switched on
 * (Player Finder, Auto Conversation, Looped Clicker, Hollows Walk, Pet Swapper, remote commands from the website).
 */
public class FemboyClientMod implements ClientModInitializer {

	/** Mod id, matches the "id" field in fabric.mod.json. */
	public static final String MOD_ID = "femboyclient";

	/** Shared logger. Output shows up in the game log prefixed with (femboyclient). */
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Tells the website whether this account is in SkyBlock. */
	private static final SkyBlockStatus SKYBLOCK_STATUS = new SkyBlockStatus();

	/** Reports the usernames in your lobby to the website. */
	public static final PlayerTracker PLAYER_TRACKER = new PlayerTracker();

	/** Knows when you are in the Crystal Hollows and which lobby, from the sidebar. Nothing goes to the website. */
	public static final HollowsTracker HOLLOWS = new HollowsTracker(
			(lobby, structure, x, y, z, at, scan) -> {
			},
			(lobby, structure, box, jasper, kinds, at) -> {
			},
			() -> FemboyClientConfig.get().enrollSecret);

	/** Recognises the Mansion in the chunks the game has loaded, by what it is made of. */
	public static final MansionFinder MANSION_FINDER = new MansionFinder();

	/** MansionFinder: feeds the scanner and puts the Mansions it finds on the corner map. */
	public static final HollowsSync HOLLOWS_SYNC = new HollowsSync(HOLLOWS, MANSION_FINDER);

	/** Notices who joins your party and reports them to the website. */
	public static final PartyTracker PARTY_TRACKER = new PartyTracker(
			payload -> FemboyClientMod.SERVER.enqueueTracker(payload),
			() -> FemboyClientConfig.get().enabled,
			() -> Account.current().name());

	/** Collects what other players say, and what you say, and reports it to the website (Chat Logs, and the Chat tab on a player). */
	public static final PlayerChatCollector CHAT_COLLECTOR = new PlayerChatCollector(
			payload -> FemboyClientMod.SERVER.enqueueTracker(payload),
			() -> FemboyClientConfig.get().enabled,
			() -> FemboyClientConfig.get().enabled,
			() -> Account.current().name(),
			PLAYER_TRACKER::lobbyNames,
			PLAYER_TRACKER::knownWorld);

	/** The Player Finder's hop loop. Only does anything while the website has given it a target. */
	public static final FinderHop FINDER = new FinderHop(new FinderGame(), () -> FemboyClientConfig.get().enabled);

	/** Links the account to the website, sends the trackers' messages and reports status, on a background thread. */
	public static final ServerLink SERVER = new ServerLink(
			WebClient.withFallbacks(() -> FemboyClientConfig.get().serverUrlCandidates()),
			FemboyClientConfig::get,
			Account::current,
			Chat::notice);

	/** Sends a small picture of the game screen to the website's Live tab. Always on, and never says anything in chat. */
	public static final LiveView LIVE = new LiveView(FemboyClientConfig::get, SERVER::linked, SERVER::uploadFrame, SERVER::takeLiveInput);

	/** Auto Conversation: clicks the right option of a Hypixel NPC dialog for you. On by default; switch it in the G menu. */
	public static final NpcAutoAnswer NPC_AUTO = new NpcAutoAnswer(
			new NpcAutoGame(), () -> FemboyClientConfig.get().enabled && FemboyClientConfig.get().npcAuto);

	/** Downloads a newer mod jar from the website and installs it after the game closes. Null when this is not a real jar (a dev run). */
	private static ModUpdater updater;

	/** Starts the website on this PC with the game, when serverDir is set, and keeps it running. */
	public static final ServerLauncher SERVER_LAUNCHER = ServerLauncher.create(FemboyClientConfig::get);

	private static boolean hollowsWarned;

	/** Never throws: the map must not be able to disturb the game. Says so once, not every tick. */
	private static void tickHollows(net.minecraft.client.Minecraft client) {
		try {
			HOLLOWS.tick(() -> PLAYER_TRACKER.hollowsReading(client), java.time.Instant.now());
			if (FemboyClientConfig.get().enabled) {
				HOLLOWS_SYNC.tick(client);
			}
		} catch (RuntimeException e) {
			if (!hollowsWarned) {
				hollowsWarned = true;
				LOGGER.warn("hollows map failed, it will keep trying", e);
			}
		}
	}

	/**
	 * Looks for a newer jar five seconds after the game starts and then every thirty seconds (every minute on GitHub), for as long
	 * as the game runs, so a new jar is staged well before you'd actually get around to closing the game. A jar built with
	 * updateRepo and updateToken updates from GitHub releases and needs no website; any other jar updates from the website once
	 * linked. Always on: there is no setting to turn this off. Never throws.
	 */
	private static void startUpdater() {
		try {
			Path jar = FabricLoader.getInstance().getModContainer(MOD_ID)
					.flatMap(container -> container.getOrigin().getPaths().stream().findFirst()).orElse(null);
			if (!ModUpdater.replaceable(jar)) {
				LOGGER.info("auto update: not checking, this is a dev run, not a real jar ({})", jar);
				return;
			}
			FemboyClientConfig config = FemboyClientConfig.get();
			if (config.updatesFromGitHub()) {
				GitHubReleases releases = new GitHubReleases(GitHubReleases.API, config.updateRepo, config.updateToken, Chat::notice);
				updater = new ModUpdater(jar, releases, ModUpdater.processSwapper(), Chat::notice);
				Daemons.scheduler("update").scheduleWithFixedDelay(updater::check, 5, 60, java.util.concurrent.TimeUnit.SECONDS);
				LOGGER.info("auto update: from the GitHub releases of {}", config.updateRepo);
				return;
			}
			updater = new ModUpdater(jar, SERVER::getWithKey, ModUpdater.processSwapper(), Chat::notice);
			var checker = Daemons.scheduler("update");
			boolean[] skipReasonLogged = {false};
			checker.scheduleWithFixedDelay(() -> {
				if (!SERVER.linked()) {
					logSkipOnce(skipReasonLogged, "this account is not linked to the website yet (no API key)");
				} else {
					skipReasonLogged[0] = false;                 // linked now: log again if it drops out later
					updater.check();
				}
			}, 5, 30, java.util.concurrent.TimeUnit.SECONDS);
		} catch (RuntimeException e) {
			LOGGER.warn("auto update could not start", e);
		}
	}

	/** Logs why the update check is being skipped, once per reason, so it can be diagnosed from the log without chat spam. */
	private static void logSkipOnce(boolean[] logged, String reason) {
		if (!logged[0]) {
			logged[0] = true;
			LOGGER.info("auto update: not checking, {}", reason);
		}
	}

	/** One incoming chat line (system or player) for the trackers that read chat. Our own messages are skipped. */
	private static void onChatLine(net.minecraft.network.chat.Component message) {
		if (!Chat.isSendingOwn()) {
			String line = ScoreboardReader.strip(message.getString());
			PARTY_TRACKER.onLine(line);
			KeyHighlight.onChatLine(line);
			ChatCommands.onChatLine(line);
			CHAT_COLLECTOR.onLine(line);
			PetSwapper.onChatLine(line);
		}
	}
	@Override
	public void onInitializeClient() {
		// Read config first so the chat listeners know the privacy settings.
		FemboyClientConfig.get();

		// Start the website first, so it is ready by the time the account links and the scanner has something to send.
		SERVER_LAUNCHER.start();

		// Auto Conversation: clicks the right option of an NPC dialog for you (its own chat listener, see NpcAutoAnswer).
		NPC_AUTO.register();

		// Party membership comes from chat: who joined, left, or spoke in party chat.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				onChatLine(message);
			}
		});
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, receptionTime) -> onChatLine(message));

		// A Fairy Grotto is looked for in a chunk the moment the game has loaded it, not at the next sweep.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (HOLLOWS_SYNC.scanning()) {
				MANSION_FINDER.chunkLoaded(chunk.getPos().x(), chunk.getPos().z());
			}
		});

		// Runs at the end of every client tick. The tracker throttles itself to once a second.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			SKYBLOCK_STATUS.onClientTick(client);
			PLAYER_TRACKER.onClientTick(client);
			tickHollows(client);
			LIVE.tick(client);
			NPC_AUTO.tick(System.nanoTime());
			CHAT_COLLECTOR.tick();
			PlayerInfoKey.onClientTick(client);
			FrienemiesSync.tick();
			Keepsprint.tick(client);
			Dungeons.tick(client);
			ServerStats.tick(client);
			GhostBlocks.tick(client);
			TeammatesHighlight.tick(client);
			PuzzleSolvers.tick(client);
			CameraTweaks.tick(client);
			ChatCommands.tick(client);
			ChestEsp.tick(client);
			StarredMobs.tick(client);
			KeyHighlight.tick(client);
			LoopedClicker.tick(client);
			PetSwapper.tick(client);
			FINDER.tick();
			Chat.onTick(client);
		});

		// Leaving a server ends the session immediately rather than waiting for the grace period.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SKYBLOCK_STATUS.onDisconnect();
			PLAYER_TRACKER.onDisconnect();
			HOLLOWS.reset();
			MANSION_FINDER.reset();
			PARTY_TRACKER.onDisconnect();
			CHAT_COLLECTOR.onDisconnect();
			FINDER.onDisconnect();
			PetSwapper.onDisconnect();
		});

		// The MansionFinder map in the corner of the screen. Everything below is reached from the G menu, not a chat command.
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
				net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "hollows_map"), HollowsHud::extract);
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
				net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "frienemys"), FrienemiesHud::extract);
		HollowsMapDrag.register();
		LoopedClickerOverlay.register();
		PlayerInfoKey.register();
		WikiBar.register();
		CopyChat.register();
		ChestEsp.register();
		StarredMobs.register();
		PuzzleSolvers.register();
		ChatCommands.register();
		ProxyButton.register();
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
				net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "server_display"), ServerDisplay::extract);

		// Link this account to the website and start uploading. Does nothing until
		// an enrollSecret (or apiKey) is in the config.
		SERVER.setFinderListener(FINDER::onServerCommand);
		SERVER.setCommandRunner(new RemoteCommands());           // quit or chat, sent from the page
		SERVER.setScanner(Scanner.describe(FemboyClientConfig.get()));
		SERVER.start();
		LIVE.startInput();
		startUpdater();

		// Send what is left, and mark the account offline, when the game closes.
		// The website is left running for the other PCs; only the checking stops.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			LIVE.stop();
			SERVER.stop();
			SERVER_LAUNCHER.stop();
		});

		LOGGER.info("FemboyClient loaded.");
	}
}
