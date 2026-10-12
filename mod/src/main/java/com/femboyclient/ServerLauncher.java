package com.femboyclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Starts the website on this PC when the game starts, and starts it again if it stops while the game is open, so the
 * scanner and uploads always have somewhere to go.
 *
 * It only does anything when serverDir in the config is a folder with the website in it (app.py and .venv) and serverUrl
 * points at this PC. On another PC, where serverUrl is the host's address and the folder does not exist, it does nothing.
 *
 * The website is started the same way as server.bat forever: .venv\Scripts\python.exe -m uvicorn app:app --host 0.0.0.0,
 * on the port in serverUrl, with its output added to server.log and server.err.log. It is left running when the game
 * closes, because the other PCs use it too; the next launch finds it already up and leaves it alone.
 *
 * Dependencies are passed in so check() can be tested without starting Python.
 */
public final class ServerLauncher {

	private static final Logger LOGGER = LoggerFactory.getLogger(FemboyClientMod.MOD_ID);

	/** How often the website is checked while the game is open. */
	static final Duration CHECK_EVERY = Duration.ofSeconds(10);

	/** How long a freshly started website gets to answer before it counts as down. */
	static final Duration STARTUP_GRACE = Duration.ofSeconds(30);

	/** A website that exits sooner than this after starting has crashed, so the next try waits longer. */
	static final Duration CRASH_WINDOW = Duration.ofSeconds(15);

	static final Duration FIRST_BACKOFF = Duration.ofSeconds(30);
	static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

	/**
	 * A website this mod did not start must miss this many checks in a row before another is started. server.bat forever
	 * restarts its own within about 7 seconds, and two copies would fight over the port.
	 */
	static final int MISSES_BEFORE_START = 2;

	/** A started website, as far as the launcher needs to know. */
	interface Handle {
		boolean isAlive();

		/** Only asked once isAlive() is false. */
		int exitCode();
	}

	/** Starts the website. Throws IOException if the process cannot be created at all. */
	@FunctionalInterface
	interface Starter {
		Handle start(Path serverDir, int port) throws IOException;
	}

	private final Supplier<FemboyClientConfig> config;
	private final BooleanSupplier healthy;
	private final Starter starter;
	private final LongSupplier clock;

	private ScheduledExecutorService scheduler;

	// Everything below is only touched by check(), which runs on one thread.
	private Handle process;
	private long startedAt;
	private int misses;
	private long waitUntil;
	private Duration backoff = FIRST_BACKOFF;
	private String skipReason;
	private boolean announcedUp;
	private boolean checkedOnce;
	private boolean warnedHung;

	ServerLauncher(Supplier<FemboyClientConfig> config, BooleanSupplier healthy, Starter starter, LongSupplier clock) {
		this.config = config;
		this.healthy = healthy;
		this.starter = starter;
		this.clock = clock;
	}

	/** The real one: checks GET /health and starts Python. */
	public static ServerLauncher create(Supplier<FemboyClientConfig> config) {
		WebClient web = new WebClient(() -> config.get().serverUrl);
		return new ServerLauncher(config,
				() -> web.get("/health", Duration.ofSeconds(2), Map.of()).join().ok(),
				ServerLauncher::startPython,
				System::nanoTime);
	}

	/** Starts checking on a background thread. The first check runs at once, so the website is starting before the title screen. */
	public void start() {
		scheduler = Daemons.scheduler("server-launcher");
		scheduler.scheduleWithFixedDelay(this::check, 0, CHECK_EVERY.toSeconds(), TimeUnit.SECONDS);
	}

	/** Stops checking. The website itself is left running. */
	public void stop() {
		if (scheduler != null) {
			scheduler.shutdownNow();
		}
	}

	/** One check: start the website if it should be running here and is not. Package-private for tests. Never throws. */
	void check() {
		try {
			runCheck();
		} catch (RuntimeException e) {
			LOGGER.warn("server launcher: check failed", e);
		}
	}

	private void runCheck() {
		FemboyClientConfig cfg = config.get();
		String reason = whyNot(cfg);
		if (reason != null) {
			if (!reason.equals(skipReason)) {
				skipReason = reason;
				LOGGER.info("server launcher: not starting the website on this PC: {}", reason);
			}
			return;
		}
		skipReason = null;
		boolean first = !checkedOnce;
		checkedOnce = true;

		if (healthy.getAsBoolean()) {
			misses = 0;
			backoff = FIRST_BACKOFF;
			if (!announcedUp) {
				announcedUp = true;
				LOGGER.info("server launcher: the website is up at {}", cfg.serverUrl);
			}
			return;
		}
		announcedUp = false;

		long now = clock.getAsLong();
		if (process != null) {
			if (process.isAlive()) {
				if (now - startedAt >= STARTUP_GRACE.toNanos() && !warnedHung) {
					warnedHung = true;
					LOGGER.warn("server launcher: the website was started {}s ago but does not answer; see server.err.log",
							Duration.ofNanos(now - startedAt).toSeconds());
				}
				return;                                     // starting, or stuck: never start a second copy beside a live one
			}
			int code = process.exitCode();
			boolean crashed = now - startedAt < CRASH_WINDOW.toNanos();
			process = null;
			misses = MISSES_BEFORE_START;                   // ours died: no need to wait for a second miss
			if (crashed) {
				LOGGER.warn("server launcher: the website exited straight away (code {}), trying again in {}s; see {}",
						code, backoff.toSeconds(), Path.of(cfg.serverDir).resolve("server.err.log"));
				waitLonger(now);
				return;
			}
			LOGGER.warn("server launcher: the website stopped (code {}), starting it again", code);
		} else {
			misses++;
		}

		if (now < waitUntil) {
			return;
		}
		// At launch a miss means nobody is running it, so start at once. Later, a website someone else started gets a
		// second chance, in case its own watchdog (server.bat forever) is restarting it.
		if (!first && misses < MISSES_BEFORE_START) {
			return;
		}
		launch(cfg, now);
	}

	private void launch(FemboyClientConfig cfg, long now) {
		Path dir = Path.of(cfg.serverDir);
		int port = port(cfg.serverUrl);
		try {
			process = starter.start(dir, port);
			startedAt = now;
			warnedHung = false;
			LOGGER.info("server launcher: started the website from {} on port {}", dir, port);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("server launcher: could not start the website from {}, trying again in {}s: {}",
					dir, backoff.toSeconds(), e.toString());
			waitLonger(now);
		}
	}

	/** Holds off the next start, twice as long each time it goes wrong, up to MAX_BACKOFF. */
	private void waitLonger(long now) {
		waitUntil = now + backoff.toNanos();
		Duration doubled = backoff.multipliedBy(2);
		backoff = doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
	}

	/** Why the website should not be started from this PC, or null if it should. Package-private for tests. */
	static String whyNot(FemboyClientConfig cfg) {
		if (!cfg.enabled) {
			return "the mod is switched off";
		}
		if (cfg.serverDir.isBlank()) {
			return "serverDir is not set";
		}
		Path dir;
		try {
			dir = Path.of(cfg.serverDir);
		} catch (RuntimeException e) {
			return "serverDir is not a valid path";
		}
		if (!Files.isRegularFile(dir.resolve("app.py"))) {
			return "no app.py in " + dir;
		}
		if (!Files.isRegularFile(python(dir))) {
			return "no " + python(dir) + " (set up the website's .venv first)";
		}
		String host = host(cfg.serverUrl);
		if (host == null) {
			return "serverUrl is not a valid address";
		}
		if (!isThisPc(host)) {
			return "serverUrl points at another PC (" + host + ")";
		}
		return null;
	}

	/** The host in a server address, or null if it has none. */
	static String host(String serverUrl) {
		try {
			return URI.create(serverUrl.trim()).getHost();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The port in a server address; 8000 if it names none, like the .bat files. */
	static int port(String serverUrl) {
		try {
			int port = URI.create(serverUrl.trim()).getPort();
			return port > 0 ? port : 8000;
		} catch (RuntimeException e) {
			return 8000;
		}
	}

	/** True if the host is this PC: localhost or one of its own network addresses. Package-private for tests. */
	static boolean isThisPc(String host) {
		try {
			for (InetAddress address : InetAddress.getAllByName(host)) {
				if (address.isLoopbackAddress() || address.isAnyLocalAddress()
						|| NetworkInterface.getByInetAddress(address) != null) {
					return true;
				}
			}
		} catch (UnknownHostException | SocketException e) {
			return false;
		}
		return false;
	}

	private static Path python(Path serverDir) {
		return serverDir.resolve(".venv").resolve("Scripts").resolve("python.exe");
	}

	/** Starts uvicorn like server.bat forever does. Java creates it without a window, and it keeps running after the game exits. */
	private static Handle startPython(Path dir, int port) throws IOException {
		Path out = dir.resolve("server.log");
		Path err = dir.resolve("server.err.log");
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		Files.writeString(out, "[" + stamp + "] started by the Femboy Client mod" + System.lineSeparator(),
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

		Process process = new ProcessBuilder(List.of(python(dir).toString(), "-m", "uvicorn", "app:app",
				"--host", "0.0.0.0", "--port", Integer.toString(port)))
				.directory(dir.toFile())
				.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile()))
				.redirectError(ProcessBuilder.Redirect.appendTo(err.toFile()))
				.start();
		return new Handle() {
			@Override
			public boolean isAlive() {
				return process.isAlive();
			}

			@Override
			public int exitCode() {
				return process.exitValue();
			}
		};
	}
}
