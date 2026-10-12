package com.femboyclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Everything to do with talking to the website, on one background thread so the
 * game never waits for the network.
 *
 * What it does every few seconds:
 *   1. Links this account to the website if that has not happened yet. The mod
 *      sends the account's UUID and name plus the shared enrol secret, and gets
 *      that account's API key back. Nothing has to be copied between PCs.
 *   2. Uploads queued coin rows.
 *   3. Sends a heartbeat every 15 seconds, and whenever the SkyBlock status
 *      changes, so the website can show which accounts are online.
 *   4. Sends the player tracker's lobby messages (POST /tracker/snapshot) as soon
 *      as they are queued, over the same connection and with the same key.
 *   5. Asks for commands sent from the page (quit, or a chat message) on a thread
 *      of its own, has the game do them and reports how each went.
 *
 * If the website cannot be reached, rows are kept and retried, and saved to disk
 * so they survive a restart. The player is told in chat when the connection is
 * made and when it drops.
 *
 * Dependencies are passed in so the whole thing can be tested without Minecraft.
 */
public final class ServerLink {

	private static final Logger LOGGER = LoggerFactory.getLogger(FemboyClientMod.MOD_ID);

	static final String CONNECTED_MESSAGE = "Connected to the server! Meow!";
	static final String DISCONNECTED_MESSAGE = "Disconnected from server! >_<";

	/** Stop the queue growing forever if the website is down for days. */
	private static final int MAX_BACKLOG_ROWS = 10_000;

	/** Player tracker messages kept while the website is unreachable. */
	private static final int MAX_TRACKER_BACKLOG = 200;

	/** Rows per request. The server accepts up to 2000. */
	private static final int CHUNK_ROWS = 500;

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
	private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration HEARTBEAT_EVERY = Duration.ofSeconds(15);
	Duration heartbeatEvery = HEARTBEAT_EVERY;             // a test shortens it
	static final Duration HEARTBEAT_CHECK_EVERY = Duration.ofSeconds(3);

	private final WebClient web;
	private final Supplier<FemboyClientConfig> config;
	private final Supplier<AccountInfo> account;
	private final Consumer<String> notifier;
	private final Path queueFile;

	/** Rows handed over by the client thread. The only thing it touches. */
	private final ConcurrentLinkedQueue<JsonObject> pending = new ConcurrentLinkedQueue<>();

	/** Rows waiting to be sent, oldest first. Guarded by lock. */
	private final ArrayDeque<JsonObject> backlog = new ArrayDeque<>();

	/** Player tracker messages handed over by the client thread. */
	private final ConcurrentLinkedQueue<JsonObject> trackerPending = new ConcurrentLinkedQueue<>();

	/** Player tracker messages waiting to be sent, oldest first. Guarded by lock. */
	private final ArrayDeque<JsonObject> trackerBacklog = new ArrayDeque<>();

	/** Held while a tick runs, so the shutdown flush cannot overlap one. */
	private final ReentrantLock lock = new ReentrantLock();

	private ScheduledExecutorService scheduler;
	private ScheduledExecutorService finderScheduler;
	private ScheduledExecutorService heartbeatScheduler;

	/** Told what the website wants the Player Finder to do. Set once at start-up. */
	private volatile java.util.function.Consumer<FinderHop.Command> finderListener;

	/** How often the mod asks the website for the finder's command. */
	static final Duration FINDER_POLL_EVERY = Duration.ofSeconds(2);

	private ScheduledExecutorService commandScheduler;

	/** Runs the commands sent from the page (quit, chat) in the game. Set once at start-up. */
	private volatile CommandRunner commandRunner;

	/** How often the mod asks the website for a command sent from the page. */
	static final Duration COMMAND_POLL_EVERY = Duration.ofSeconds(3);

	/** How long a command may take in the game before it is reported as failed. The website gives up after 30s. */
	static final Duration COMMAND_RUN_TIMEOUT = Duration.ofSeconds(10);

	/** A command sent from the page. type is QUIT or CHAT; payload is the message for CHAT. */
	public record RemoteCommand(long id, String type, String payload) {
	}

	/** Does a command in the game. Completes with null when done, or with the reason it could not be done. */
	@FunctionalInterface
	public interface CommandRunner {
		CompletableFuture<String> run(RemoteCommand command);
	}
	private volatile boolean active;

	// What the heartbeat reports. Written by the client thread, read by ours.
	private volatile boolean inSkyBlock;
	private volatile String scannerStatus = "";
	private volatile String sessionId;
	private volatile boolean statusDirty = true;

	// Everything below is guarded by lock.
	private volatile String apiKey;
	private boolean keyFromRegistration;
	private Boolean connected;          // null until we know either way
	private boolean failing;
	private long lastContactNanos;      // 0 means never
	private volatile long lastHeartbeatNanos;   // 0 means never; only a heartbeat that the website accepted sets it
	private final Object heartbeatLock = new Object();
	private boolean warnedNoCredentials;
	private boolean warnedBacklogDrop;
	private boolean warnedTrackerDrop;
	private final Set<String> refusalsLogged = new HashSet<>();
	private Duration timeout = REQUEST_TIMEOUT;

	public ServerLink(WebClient web, Supplier<FemboyClientConfig> config, Supplier<AccountInfo> account,
			Consumer<String> notifier, Path queueFile) {
		this.web = web;
		this.config = config;
		this.account = account;
		this.notifier = notifier;
		this.queueFile = queueFile;
	}

	// ------------------------------------------------------------- lifecycle

	/** Starts the background thread. Does nothing if uploads are off or there are no credentials. */
	public void start() {
		FemboyClientConfig cfg = config.get();

		if (!cfg.enabled) {
			LOGGER.info("Femboy Client is switched off in the config, nothing will be uploaded");
			return;
		}
		if (cfg.apiKey.isBlank() && cfg.enrollSecret.isBlank()) {
			LOGGER.info("upload disabled: set enrollSecret in config/femboyclient.properties to link this account");
			return;
		}

		activate();

		int seconds = Math.max(1, cfg.uploadIntervalSeconds);

		// A daemon thread so it never keeps the game process alive on quit.
		scheduler = Daemons.scheduler("uploader");

		// The short first delay links the account soon after launch, well before
		// the player has joined anything.
		scheduler.scheduleWithFixedDelay(this::tick, 2, seconds, TimeUnit.SECONDS);
		LOGGER.info("upload enabled: {} every {}s", cfg.serverUrl, seconds);

		// "Still here" goes out on its own thread too. It used to wait behind the uploads and to count any successful request as
		// contact, so while the Hollows map was sending its own updates every few seconds it almost never went out and the website
		// showed the account going offline and coming back.
		heartbeatScheduler = Daemons.scheduler("heartbeat");
		heartbeatScheduler.scheduleWithFixedDelay(this::heartbeatTick, 3, HEARTBEAT_CHECK_EVERY.toSeconds(), TimeUnit.SECONDS);

		// The Player Finder's command is asked for on its own thread, so a slow upload never delays it.
		finderScheduler = Daemons.scheduler("finder");
		finderScheduler.scheduleWithFixedDelay(this::pollFinder, 4, FINDER_POLL_EVERY.toSeconds(), TimeUnit.SECONDS);

		// Commands from the page get their own thread too: waiting for the game to send a chat line never holds up the finder.
		commandScheduler = Daemons.scheduler("commands");
		commandScheduler.scheduleWithFixedDelay(this::pollCommands, 5, COMMAND_POLL_EVERY.toSeconds(), TimeUnit.SECONDS);
	}

	/** Who runs the commands sent from the page. */
	public void setCommandRunner(CommandRunner runner) {
		this.commandRunner = runner;
	}

	/**
	 * Asks the website for a command sent from the page, has the game do it and reports how it went. Does nothing until the
	 * account is linked. Never throws.
	 *
	 * A quit is reported as done before it is started, because once the game is closing there is no one left to report it.
	 * A chat message is plain text only: anything starting with "/" is refused here as well as by the website.
	 */
	void pollCommands() {
		try {
			CommandRunner runner = commandRunner;
			String key = apiKey;
			if (runner == null || key == null || !active || !config.get().enabled) {
				return;
			}

			WebClient.Response response = web.get("/commands/next", Duration.ofSeconds(4), Map.of("X-API-Key", key)).join();
			if (response.status() != 200 || response.body() == null) {
				return;
			}
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			if (json.get("command") == null || json.get("command").isJsonNull()) {
				return;
			}
			JsonObject raw = json.getAsJsonObject("command");
			RemoteCommand command = new RemoteCommand(raw.get("id").getAsLong(), raw.get("type").getAsString(),
					raw.has("payload") && !raw.get("payload").isJsonNull() ? raw.get("payload").getAsString() : null);

			String failure = switch (command.type()) {
				case "QUIT" -> null;
				case "CHAT" -> command.payload() == null || command.payload().isBlank() ? "no message to send"
						: command.payload().startsWith("/") ? "only chat messages can be sent, not commands" : null;
				default -> "this version of the mod does not know " + command.type();
			};
			if (failure != null) {
				reportCommand(key, command.id(), failure);
				return;
			}

			if (command.type().equals("QUIT")) {
				reportCommand(key, command.id(), null);
				runner.run(command);
				return;
			}

			String outcome;
			try {
				outcome = runner.run(command).get(COMMAND_RUN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				outcome = "the game did not send it in time";
			} catch (java.util.concurrent.ExecutionException e) {
				outcome = "the game could not send it";
			}
			reportCommand(key, command.id(), outcome);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			// A bad answer is ignored. The next poll tries again, and the website fails a command nobody answers.
		}
	}

	/** Tells the website how a command went: failure is null when it was done. */
	private void reportCommand(String key, long id, String failure) {
		JsonObject body = new JsonObject();
		body.addProperty("status", failure == null ? "DONE" : "FAILED");
		if (failure != null) {
			body.addProperty("reason", failure);
		}
		WebClient.Response response = web.post("/commands/" + id + "/result", body.toString(), Duration.ofSeconds(4),
				Map.of("X-API-Key", key)).join();
		if (!response.ok()) {
			LOGGER.warn("could not report command {} to the website: {}", id, response.describe());
		}
	}

	/** Who to tell when the website changes what the Player Finder should do. */
	public void setFinderListener(java.util.function.Consumer<FinderHop.Command> listener) {
		this.finderListener = listener;
	}

	/**
	 * Asks the website what the Player Finder should be doing and passes the answer on: a target
	 * and a world, or null for nothing. Does nothing until the account is linked. Never throws.
	 */
	void pollFinder() {
		try {
			java.util.function.Consumer<FinderHop.Command> listener = finderListener;
			String key = apiKey;
			if (listener == null || key == null || !active || !config.get().enabled) {
				return;
			}

			WebClient.Response response = web.get("/finder/command", Duration.ofSeconds(4), Map.of("X-API-Key", key)).join();
			if (response.status() != 200 || response.body() == null) {
				return;                     // no answer counts as no news: the loop ends a search if this goes on
			}

			com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
			if (json.get("target") == null || json.get("target").isJsonNull()) {
				listener.accept(null);
			} else {
				listener.accept(new FinderHop.Command(json.get("target").getAsString(),
						json.has("world") && !json.get("world").isJsonNull() ? json.get("world").getAsString() : "",
						json.has("run") ? json.get("run").getAsLong() : 0));
			}
		} catch (RuntimeException e) {
			// A bad answer is ignored. The next poll tries again.
		}
	}

	/** Accepts rows and loads any saved from last time. Separate from start() so tests can drive tick() themselves. */
	void activate() {
		lock.lock();
		try {
			loadQueueFile();
			active = true;
		} finally {
			lock.unlock();
		}
	}

	/** Flushes what is left, tells the website this account is going offline, and stops. */
	public void stop() {
		if (!active) {
			return;
		}

		if (heartbeatScheduler != null) {
			heartbeatScheduler.shutdownNow();
		}
		if (finderScheduler != null) {
			finderScheduler.shutdownNow();
		}
		if (commandScheduler != null) {
			commandScheduler.shutdownNow();
		}
		if (scheduler != null) {
			scheduler.shutdown();
			try {
				scheduler.awaitTermination(2, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}

		boolean locked;
		try {
			locked = lock.tryLock(SHUTDOWN_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		if (!locked) {
			LOGGER.warn("the upload thread is busy, skipping the final flush (the CSV still has every row)");
			return;
		}

		try {
			timeout = SHUTDOWN_TIMEOUT;
			drainPending();
			if (apiKey != null && flushBacklog()) {
				flushTracker();
				sendHeartbeat(false);
			} else if (!backlog.isEmpty()) {
				saveQueueFile();
			}
		} catch (RuntimeException e) {
			LOGGER.warn("final upload failed", e);
		} finally {
			lock.unlock();
		}
	}

	// ---------------------------------------------------- called by the game

	/** Called from the client thread. Must be fast and must never throw. */
	public void enqueue(CoinRow row) {
		if (active) {
			pending.add(row.toJson());
		}
	}

	/**
	 * Called from the client thread. Queues a player tracker message and sends it
	 * straight away, so players appear on the website within seconds. Must be fast
	 * and must never throw.
	 */
	public void enqueueTracker(JsonObject payload) {
		if (active) {
			trackerPending.add(payload);
			wake();
		}
	}

	/** Runs a round of work now instead of waiting for the timer. */
	private void wake() {
		ScheduledExecutorService running = scheduler;
		if (running == null || running.isShutdown()) {
			return;
		}
		try {
			running.execute(this::tick);
		} catch (RejectedExecutionException e) {
			// Shutting down; stop() sends what is left.
		}
	}

	/** Tells the website whether the scanner is on, for example "ON (world: Hub)". Sent with the next heartbeat. */
	public void setScanner(String status) {
		if (!Objects.equals(this.scannerStatus, status)) {
			this.scannerStatus = status;
			this.statusDirty = true;
		}
	}

	/** Tells the website whether this account is in SkyBlock, sent with the next heartbeat. */
	public void setStatus(boolean inSkyBlock, String sessionId) {
		if (this.inSkyBlock != inSkyBlock || !Objects.equals(this.sessionId, sessionId)) {
			this.inSkyBlock = inSkyBlock;
			this.sessionId = sessionId;
			this.statusDirty = true;
		}
	}

	/** Every player tagged friendly or enemy. Shared across every account; needs no key. Completes with the reply, never fails. */
	public CompletableFuture<WebClient.Response> frienemies() {
		return web.get("/tracker/frienemies", REQUEST_TIMEOUT, Map.of());
	}

	/** Tags a player friendly or enemy, replacing any tag and note they already had. Needs no key. */
	public CompletableFuture<WebClient.Response> tagFrienemy(String name, UUID uuid, String tag, String notes) {
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		if (uuid != null) {
			body.addProperty("uuid", uuid.toString());
		}
		body.addProperty("tag", tag);
		body.addProperty("notes", notes);
		return web.post("/tracker/frienemies", body.toString(), REQUEST_TIMEOUT, Map.of());
	}

	/** Removes a player's friendly/enemy tag. Needs no key. */
	public CompletableFuture<WebClient.Response> untagFrienemy(int playerId) {
		return web.delete("/tracker/frienemies/" + playerId, REQUEST_TIMEOUT, Map.of());
	}

	/** The website looks up Mojang and Hypixel and works out a networth for this, which can take several seconds. */
	private static final Duration PLAYER_INFO_TIMEOUT = Duration.ofSeconds(45);

	/**
	 * Asks the website what it knows about a player. Returns at once; the answer arrives on a
	 * background thread, so call it from the game thread freely but do not touch the game from the callback.
	 * The future always completes with a PlayerInfo (status ERROR if anything went wrong) and never fails.
	 */
	public CompletableFuture<PlayerInfo> playerInfo(UUID player, String name) {
		String key = apiKey;
		if (key == null) {
			return CompletableFuture.completedFuture(PlayerInfo.failed("This account is not linked to the website yet."));
		}
		String path = "/api/player/" + player + "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
		return web.get(path, PLAYER_INFO_TIMEOUT, Map.of("X-API-Key", key))
				.thenApply(PlayerInfo::fromResponse)
				.exceptionally(e -> PlayerInfo.failed("The request failed."));
	}

	/** A read from the website with this account's API key, for a file too big for a heartbeat. Blocks. Never throws. */
	public WebClient.Response getWithKey(String path) {
		String key = apiKey;
		if (key == null) {
			return new WebClient.Response(-1, "", "not linked yet");
		}
		return web.get(path, Duration.ofSeconds(60), Map.of("X-API-Key", key)).join();
	}

	/** What the Live tab has sent to this game since it last asked, as the website's JSON, or null. Blocks; call it off the game thread. */
	public String takeLiveInput() {
		String key = apiKey;
		if (key == null) {
			return null;
		}
		try {
			WebClient.Response response = web.get("/live/input?player_uuid=" + account.get().uuid(), Duration.ofSeconds(3),
					Map.of("X-API-Key", key)).join();
			return response.ok() ? response.body() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** True once this account is linked to the website (it has an API key). */
	public boolean linked() {
		return apiKey != null;
	}

	/** Whether the website answered the last time anything talked to it: null until the first attempt. For the G menu's live dot. */
	public Boolean connected() {
		return connected;
	}

	/**
	 * Sends one screen picture for the live view (a JPEG). Blocks, so call it from a background thread. Returns 1 if the website's
	 * page is watching right now, 0 if not, and -1 if the website did not take the picture. Never throws.
	 */
	public int uploadFrame(byte[] jpeg, String state) {
		String key = apiKey;
		FemboyClientConfig cfg = config.get();
		if (key == null || !cfg.enabled) {
			return -1;
		}
		try {
			// Every picture also says what the game is doing (held slot and item, menu open, where it stands, whether it takes input),
			// so the page can show it. URL-encoded, because a header may only hold plain characters.
			String about = java.net.URLEncoder.encode(state, StandardCharsets.UTF_8).replace("+", "%20");
			WebClient.Response response = web.postBytes("/live/frame?player_uuid=" + account.get().uuid(), jpeg, "image/jpeg",
					Duration.ofSeconds(4), Map.of("X-API-Key", key, "X-Live-State", about)).join();
			if (!response.ok()) {
				return -1;
			}
			JsonObject reply = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
			return reply.has("viewers") && reply.get("viewers").getAsInt() > 0 ? 1 : 0;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	/** Where the website is, for showing in chat. */
	public String serverUrl() {
		return web.currentUrl().isEmpty() ? config.get().serverUrl : web.currentUrl();
	}

	// ------------------------------------------------------------------ tick

	/** One round of work. Package-private so tests can run it directly. */
	void tick() {
		lock.lock();
		try {
			runTick();
		} catch (RuntimeException e) {
			// Never let an exception kill the scheduled task.
			LOGGER.warn("upload tick failed", e);
		} finally {
			lock.unlock();
		}
	}

	private void runTick() {
		FemboyClientConfig cfg = config.get();
		if (!cfg.enabled) {
			return;
		}
		if (!ensureKey(cfg)) {
			return;
		}

		drainPending();
		if (!flushBacklog()) {
			return; // the website is having trouble; try again next tick
		}

		flushTracker();

		heartbeatIfDue();
	}

	/** The heartbeat thread's round. Never throws, and needs no lock: it only talks to the website. */
	void heartbeatTick() {
		try {
			FemboyClientConfig cfg = config.get();
			if (cfg.enabled && apiKey != null && active) {
				heartbeatIfDue();
			}
		} catch (RuntimeException e) {
			LOGGER.warn("heartbeat failed", e);
		}
	}

	private void heartbeatIfDue() {
		synchronized (heartbeatLock) {
			if (statusDirty || heartbeatDue()) {
				sendHeartbeat(true);
			}
		}
	}

	// ---------------------------------------------------------- linking

	/**
	 * Makes sure we hold an API key, linking this account to the website if needed.
	 *
	 * With an enrollSecret the account that is logged in links itself by its UUID and name, so the same config works for every
	 * account you play on and each one uploads into the website's shared tables under its own name. A key typed into the config
	 * belongs to one account, so it is only used when there is no enrollSecret.
	 */
	private boolean ensureKey(FemboyClientConfig cfg) {
		if (apiKey != null && keyFromRegistration) {
			return true;                                  // already linked
		}
		if (cfg.enrollSecret.isBlank()) {
			if (!cfg.apiKey.isBlank()) {
				apiKey = cfg.apiKey;
				keyFromRegistration = false;
				return true;
			}
			if (!warnedNoCredentials) {
				warnedNoCredentials = true;
				LOGGER.warn("no enrollSecret or apiKey in the config, so this account cannot be linked");
			}
			return false;
		}

		AccountInfo who = account.get();
		JsonObject body = new JsonObject();
		body.addProperty("player_uuid", who.uuid().toString());
		body.addProperty("player_name", who.name());

		WebClient.Response response = web.post("/register", body.toString(), timeout,
				Map.of("X-Enroll-Secret", cfg.enrollSecret)).join();

		if (!response.ok()) {
			String reason = response.status() == 401 ? "the website rejected the enrollSecret" : response.describe();
			contactFailed("linking the account: " + reason);
			return false;
		}

		try {
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			apiKey = json.get("api_key").getAsString();
			keyFromRegistration = true;
			LOGGER.info("linked {} to the website ({})", who.name(),
					json.get("created").getAsBoolean() ? "new account" : "already linked");
		} catch (RuntimeException e) {
			contactFailed("linking the account: unexpected reply from the website");
			return false;
		}

		contactOk();
		return true;
	}

	// ---------------------------------------------------------- uploading

	/** Moves rows from the client thread's queue into the backlog. */
	private void drainPending() {
		JsonObject row;
		while ((row = pending.poll()) != null) {
			backlog.addLast(row);
		}

		while (backlog.size() > MAX_BACKLOG_ROWS) {
			backlog.removeFirst();
			if (!warnedBacklogDrop) {
				warnedBacklogDrop = true;
				LOGGER.warn("more than {} rows are waiting to upload, dropping the oldest", MAX_BACKLOG_ROWS);
			}
		}
	}

	/**
	 * Sends the whole backlog. Returns true if it is now empty, or false if it had
	 * to stop because the website is unavailable.
	 */
	private boolean flushBacklog() {
		while (!backlog.isEmpty()) {
			List<JsonObject> chunk = new ArrayList<>(Math.min(CHUNK_ROWS, backlog.size()));
			for (JsonObject row : backlog) {
				if (chunk.size() == CHUNK_ROWS) {
					break;
				}
				chunk.add(row);
			}

			int finished = sendRows(chunk);
			for (int i = 0; i < finished; i++) {
				backlog.removeFirst();
			}

			if (finished < chunk.size()) {
				// Rows that only exist in memory would be lost if the game closed now.
				saveQueueFile();
				return false;
			}
		}

		clearQueueFile();
		return true;
	}

	/**
	 * Uploads rows and returns how many are finished with, meaning stored by the
	 * website or dropped because it refused them. Anything after that is left to retry.
	 */
	private int sendRows(List<JsonObject> chunk) {
		JsonArray array = new JsonArray();
		chunk.forEach(array::add);

		// The website blanks anything that looks like player chat, unless this
		// header says the user switched includePlayerChat on.
		Map<String, String> headers = config.get().includePlayerChat
				? Map.of("X-API-Key", apiKey, "X-Player-Chat", "allowed")
				: Map.of("X-API-Key", apiKey);

		WebClient.Response response = web.post("/log", array.toString(), timeout, headers).join();

		if (accepted(response, "upload")) {
			LOGGER.info("uploaded {} row(s): {}", chunk.size(), response.body());
			return chunk.size();
		}
		if (retryable(response)) {
			return 0;
		}

		// The website refused these rows for good, for example HTTP 422 for a
		// malformed one. A single bad row must not block everything behind it, so
		// send them one at a time and drop only the ones that are refused.
		if (chunk.size() > 1) {
			int finished = 0;
			for (JsonObject row : chunk) {
				int one = sendRows(List.of(row));
				if (one == 0) {
					return finished;
				}
				finished += one;
			}
			return finished;
		}

		LOGGER.warn("dropping a row the website refused (HTTP {}): {}", response.status(), trim(response.body()));
		return 1;
	}

	// ------------------------------------------------------- player tracker

	/** Moves tracker messages from the client thread's queue into the backlog. */
	private void drainTracker() {
		JsonObject payload;
		while ((payload = trackerPending.poll()) != null) {
			trackerBacklog.addLast(payload);
		}

		while (trackerBacklog.size() > MAX_TRACKER_BACKLOG) {
			trackerBacklog.removeFirst();
			if (!warnedTrackerDrop) {
				warnedTrackerDrop = true;
				LOGGER.warn("more than {} player tracker messages are waiting, dropping the oldest", MAX_TRACKER_BACKLOG);
			}
		}
	}

	/**
	 * Sends the queued player tracker messages, oldest first. Stops at the first
	 * failure and tries again next tick, so messages arrive in order.
	 */
	private void flushTracker() {
		drainTracker();

		// Each pass either finishes with a message or stops, so this ends on its own.
		// The count is a second guard: whatever happens, one round sends at most the
		// messages that were waiting when it started.
		int budget = trackerBacklog.size();
		while (budget-- > 0 && !trackerBacklog.isEmpty()) {
			JsonObject payload = trackerBacklog.peekFirst();
			String label = TrackerPayload.label(payload);
			WebClient.Response response = web.post(TrackerPayload.path(payload), payload.toString(),
					timeout, Map.of("X-API-Key", apiKey)).join();

			if (accepted(response, label)) {
				trackerBacklog.removeFirst();
				LOGGER.debug("{}: sent {}: {}", label, TrackerPayload.size(payload), trim(response.body()));
			} else if (retryable(response)) {
				return;
			} else {
				// Refused for good, for example HTTP 422 for a malformed message.
				trackerBacklog.removeFirst();
				LOGGER.warn("player tracker: dropped a message the website refused (HTTP {})", response.status());
			}
		}
	}

	// ---------------------------------------------------------- heartbeat

	/** Due when 15 seconds have passed since the last heartbeat the website accepted, whatever else has been sent meanwhile. */
	private boolean heartbeatDue() {
		return lastHeartbeatNanos == 0 || System.nanoTime() - lastHeartbeatNanos >= heartbeatEvery.toNanos();
	}

	private void sendHeartbeat(boolean online) {
		AccountInfo who = account.get();

		JsonObject body = new JsonObject();
		body.addProperty("player_uuid", who.uuid().toString());
		body.addProperty("player_name", who.name());
		body.addProperty("in_skyblock", online && inSkyBlock);
		if (sessionId != null) {
			body.addProperty("session_id", sessionId);
		}
		body.addProperty("online", online);
		if (!scannerStatus.isEmpty()) {
			body.addProperty("scanner", scannerStatus);
		}

		statusDirty = false;
		WebClient.Response response = web.post("/heartbeat", body.toString(), timeout,
				Map.of("X-API-Key", apiKey)).join();

		boolean taken = accepted(response, "heartbeat");
		if (taken || !retryable(response)) {
			lastHeartbeatNanos = System.nanoTime();     // the website answered (even a refusal is an answer): not again for 15 seconds
		} else {
			statusDirty = true; // say it again once the website is back
		}
	}

	// ----------------------------------------------------- classifying results

	/**
	 * Reports the outcome to the connection tracker. Returns true only if the
	 * website accepted the request.
	 */
	private boolean accepted(WebClient.Response response, String what) {
		if (response.ok()) {
			contactOk();
			return true;
		}

		if (retryable(response)) {
			boolean keyProblem = response.status() == 401 || response.status() == 403;
			if (keyProblem && keyFromRegistration) {
				apiKey = null; // link again next tick, in case the website forgot us
			}
			contactFailed(what + ": " + (keyProblem
					? "the website rejected the API key (HTTP " + response.status() + ")"
					: response.describe()));
		} else {
			// The website is up, it just does not like this request.
			contactOk();
			if (refusalsLogged.add(what)) {
				LOGGER.warn("{}: the website refused the request (HTTP {}): {}",
						what, response.status(), trim(response.body()));
			}
		}
		return false;
	}

	/** Failures that go away by themselves or by re-linking, so the data is kept and retried. */
	private static boolean retryable(WebClient.Response response) {
		int status = response.status();
		if (status == 403 && isForAnotherAccount(response)) {
			return false;
		}
		return !response.reachable() || status >= 500 || status == 401 || status == 403
				|| status == 408 || status == 429;
	}

	/**
	 * The website's 403 for rows that belong to a different account than the key. Linking again gives the same key,
	 * so retrying never helps and would flip between connected and disconnected forever. The rows are dropped instead.
	 */
	private static boolean isForAnotherAccount(WebClient.Response response) {
		return response.body() != null && response.body().contains("may only be used for");
	}

	private void contactOk() {
		lastContactNanos = System.nanoTime();
		if (failing) {
			failing = false;
			LOGGER.info("the website is reachable again");
		}
		if (!Boolean.TRUE.equals(connected)) {
			connected = true;
			notifier.accept(CONNECTED_MESSAGE);
		}
	}

	private void contactFailed(String detail) {
		// Logged once per outage, not on every retry.
		if (!failing) {
			failing = true;
			LOGGER.warn("cannot reach the website, keeping rows to retry: {}", detail);
		}
		// Only say "disconnected" if we had actually been connected before.
		if (Boolean.TRUE.equals(connected)) {
			notifier.accept(DISCONNECTED_MESSAGE);
		}
		connected = false;
	}

	// ---------------------------------------------------------- the disk queue

	/** Reads rows saved by an earlier run. One JSON object per line. */
	private void loadQueueFile() {
		if (!Files.exists(queueFile)) {
			return;
		}
		try {
			for (String line : Files.readAllLines(queueFile, StandardCharsets.UTF_8)) {
				if (line.isBlank()) {
					continue;
				}
				try {
					backlog.addLast(JsonParser.parseString(line).getAsJsonObject());
				} catch (RuntimeException e) {
					LOGGER.warn("skipping an unreadable line in {}", queueFile);
				}
			}
		} catch (IOException e) {
			LOGGER.warn("could not read {}", queueFile, e);
		}
	}

	/** Writes the unsent rows to disk so a restart does not lose them. */
	private void saveQueueFile() {
		StringBuilder text = new StringBuilder();
		for (JsonObject row : backlog) {
			text.append(row).append(System.lineSeparator());
		}
		try {
			if (queueFile.getParent() != null) {
				Files.createDirectories(queueFile.getParent());
			}
			Files.writeString(queueFile, text.toString(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			LOGGER.warn("could not write {}", queueFile, e);
		}
	}

	private void clearQueueFile() {
		try {
			Files.deleteIfExists(queueFile);
		} catch (IOException e) {
			LOGGER.warn("could not delete {}", queueFile, e);
		}
	}

	private static String trim(String text) {
		return text.length() <= 200 ? text : text.substring(0, 200) + "...";
	}
}
