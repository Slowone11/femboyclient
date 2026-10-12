package com.femboyclient;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * The Player Finder's hop loop. It only runs while the website has given the mod a target, and it
 * does nothing else: it is separate from the scanner, which keeps scanning every lobby as usual.
 *
 * Every 5 seconds it does one step:
 *
 *   1. Is the target in the tab list? Then it stops and reports "found".
 *   2. At 30 hops it stops and reports "not_found" (limit).
 *   3. On your island? /warp to the target's world. This is a hop.
 *   4. Anywhere else (in the target's world, or another one)? /is to go back to the island. Not a hop.
 *   5. After each /warp, hops goes up by one and "searching" is reported, so the website counter is live.
 *
 * So it goes /warp, 5 seconds, /is, 5 seconds, /warp, and so on. The trip to the island and back is what
 * gives a NEW lobby: /warp to a world you are already in only puts you back at its spawn in the same lobby.
 * If two /warp in a row leave you on the island, the warp is not working (locked or wrong) and it stops.
 *
 * Rules that always hold:
 *   - never more than one command every 5 seconds, whatever happens;
 *   - the only commands ever sent are "is" and "warp <name from FinderWarps>", never text from the website;
 *   - it stops when the website turns the target off, when you disconnect, and when it has lost the website;
 *   - every step is printed in chat.
 *
 * Kept free of Minecraft classes (the game is reached through Env) so it can be tested with a fake clock.
 * tick() and onDisconnect() run on the client thread. onServerCommand() runs on the polling thread.
 */
public final class FinderHop {

	private static final Logger LOGGER = LoggerFactory.getLogger(FemboyClientMod.MOD_ID);

	static final long STEP_NANOS = 5_000_000_000L;        // one command every 5 seconds, at most
	static final int MAX_HOPS = 30;
	static final int UNKNOWN_WORLD_STEPS = 3;              // steps in a row with no SkyBlock world before giving up
	static final int FAILED_WARPS_LIMIT = 2;               // /warp commands in a row that did not take you anywhere
	static final long LOST_NANOS = 20_000_000_000L;        // no answer from the website for this long ends a search

	/** What the website wants. */
	public record Command(String target, String world, long run) {
	}

	/** Everything the loop needs from the game and the website. */
	public interface Env {
		boolean connected();

		/** The SkyBlock world you are in now, or null if it cannot be told (loading, or not SkyBlock). */
		String world();

		/** The lower-case usernames of the players in the tab list. */
		Set<String> lobbyNames();

		/** Types a command (without the slash). */
		void sendCommand(String command);

		/** Prints a line in your own chat. */
		void say(String text);

		/** Sends a status message to the website. */
		void report(JsonObject json);

		long nanos();
	}

	private final Env env;
	private final BooleanSupplier enabled;

	private record Update(Command command, long at) {
	}

	private final AtomicReference<Update> inbox = new AtomicReference<>();
	private volatile long lastPollAt;

	private boolean active;
	private Command run;
	private int hops;
	private long startedAt;
	private long nextStep;
	private long lastCommandAt;
	private boolean anyCommand;
	private int unknownSteps;
	private boolean warpSent;                              // the last command was a /warp, so the next step checks it worked
	private int failedWarps;                               // /warp commands in a row that left you on the island
	private long finishedRun = Long.MIN_VALUE;

	public FinderHop(Env env, BooleanSupplier enabled) {
		this.env = env;
		this.enabled = enabled;
		this.lastPollAt = env.nanos();
	}

	// ------------------------------------------------------------ from the website

	/**
	 * The website's answer to "what should I be doing": a target, or null for nothing. Called on
	 * every successful poll, from the polling thread. It is only noted here and acted on in tick().
	 */
	public void onServerCommand(Command command) {
		long now = env.nanos();
		lastPollAt = now;
		inbox.set(new Update(command, now));
	}

	// ---------------------------------------------------------------- the loop

	/** Called every client tick. Never throws. */
	public void tick() {
		try {
			Update update = inbox.getAndSet(null);
			if (update != null) {
				apply(update.command());
			}
			if (!active) {
				return;
			}

			long now = env.nanos();
			if (!env.connected()) {
				finish("stopped", "disconnected");
			} else if (now - lastPollAt > LOST_NANOS) {
				finish("stopped", "website_unreachable");
			} else if (now >= nextStep) {
				step(now);
			}
		} catch (RuntimeException e) {
			LOGGER.warn("finder: a step failed, stopping", e);
			if (active) {
				finish("stopped", "error");
			}
		}
	}

	/** You left the server: stop at once. */
	public void onDisconnect() {
		try {
			inbox.set(null);
			if (active) {
				finish("stopped", "disconnected");
			}
		} catch (RuntimeException e) {
			LOGGER.warn("finder: could not stop cleanly", e);
			active = false;
		}
	}

	/** True while a search is running. */
	public boolean isActive() {
		return active;
	}

	public int hops() {
		return hops;
	}

	// ------------------------------------------------------------------ inside

	private void apply(Command command) {
		if (active) {
			if (command != null && command.run() == run.run()) {
				return;                                             // the same search carries on
			}
			finish("stopped", null);                                // the website turned it off, or replaced it
		}

		if (command == null || command.run() == finishedRun || !enabled.getAsBoolean()) {
			return;
		}
		begin(command);
	}

	private void begin(Command command) {
		if (!PlayerNames.isValid(command.target())) {
			refuse(command, "bad_target");
			return;
		}
		if (FinderWarps.commandFor(command.world()) == null) {
			refuse(command, "no_warp");
			return;
		}

		long now = env.nanos();
		active = true;
		run = command;
		hops = 0;
		startedAt = now;
		unknownSteps = 0;
		warpSent = false;
		failedWarps = 0;
		// A new search never gets around the 5 second spacing after an earlier command.
		nextStep = anyCommand ? Math.max(now, lastCommandAt + STEP_NANOS) : now;

		LOGGER.info("finder: looking for {} in {}", command.target(), command.world());
		env.say("Looking for " + command.target() + " in " + command.world() + " (up to " + MAX_HOPS + " hops)");
		env.report(status("searching", null, null, null));
	}

	/** A search that cannot even start (a bad name, a world with no warp). Tells the website and moves on. */
	private void refuse(Command command, String reason) {
		finishedRun = command.run();
		env.say("Cannot search for " + command.target() + ": " + reason.replace('_', ' '));
		JsonObject json = new JsonObject();
		json.addProperty("target", command.target());
		json.addProperty("run", command.run());
		json.addProperty("status", "stopped");
		json.addProperty("hops", 0);
		json.addProperty("reason", reason);
		env.report(json);
	}

	private void step(long now) {
		if (env.lobbyNames().contains(run.target().toLowerCase(Locale.ROOT))) {
			found(now);
			return;
		}
		if (hops >= MAX_HOPS) {
			finish("not_found", "limit");
			return;
		}

		String world = env.world();
		if (world == null) {
			if (++unknownSteps >= UNKNOWN_WORLD_STEPS) {
				finish("stopped", "not_in_skyblock");
			} else {
				env.say("Waiting for the world to load…");
				nextStep = now + STEP_NANOS;
			}
			return;
		}
		unknownSteps = 0;

		boolean onIsland = FinderWarps.sameWorld(world, FinderWarps.ISLAND_WORLD);

		// The last step was a /warp. If you are still on the island, it did not take you anywhere.
		if (warpSent) {
			warpSent = false;
			if (onIsland && ++failedWarps >= FAILED_WARPS_LIMIT) {
				finish("stopped", "warp_failed");
				return;
			}
			if (!onIsland) {
				failedWarps = 0;
			}
		}

		if (onIsland) {
			hop();
		} else if (FinderWarps.sameWorld(world, run.world())) {
			send("is", "Step: /is (back to your island, so the next /warp gives a new lobby)");
		} else {
			send("is", "Step: /is (going to your island first)");
		}
	}

	/** Types /warp for the target's world, counts the hop, and tells the website. */
	private void hop() {
		String command = FinderWarps.commandFor(run.world());
		if (send(command, "Hop " + (hops + 1) + ": /" + command)) {
			hops++;
			warpSent = true;
			env.report(status("searching", null, null, null));
		}
	}

	/** Types one command, unless one was typed less than 5 seconds ago. Returns whether it was sent. */
	private boolean send(String command, String line) {
		long now = env.nanos();
		if (anyCommand && now - lastCommandAt < STEP_NANOS) {
			nextStep = lastCommandAt + STEP_NANOS;
			return false;
		}

		env.say(line);
		env.sendCommand(command);
		lastCommandAt = now;
		anyCommand = true;
		nextStep = now + STEP_NANOS;
		LOGGER.info("finder: {} (hops so far {})", line, hops);
		return true;
	}

	private void found(long now) {
		long seconds = Math.max(0, Math.round((now - startedAt) / 1_000_000_000.0));
		String world = env.world() != null ? env.world() : run.world();
		env.say("Found " + run.target() + " in " + world + " after " + hops + (hops == 1 ? " hop" : " hops")
				+ " (" + duration(seconds) + ")");
		env.report(status("found", world, seconds, null));
		end();
	}

	private void finish(String status, String reason) {
		String why = reason == null ? "" : switch (reason) {
			case "limit" -> " (limit of " + MAX_HOPS + " hops)";
			case "disconnected" -> " (disconnected)";
			default -> " (" + reason.replace('_', ' ') + ")";
		};
		env.say(("not_found".equals(status) ? "Not found after " : "Stopped after ") + hops + (hops == 1 ? " hop" : " hops") + why);
		env.report(status(status, null, null, reason));
		end();
	}

	private void end() {
		finishedRun = run.run();
		active = false;
	}

	private JsonObject status(String status, String world, Long elapsedSeconds, String reason) {
		JsonObject json = new JsonObject();
		json.addProperty("target", run.target());
		json.addProperty("run", run.run());
		json.addProperty("status", status);
		json.addProperty("hops", hops);
		if (world != null) {
			json.addProperty("world", world);
		}
		if (elapsedSeconds != null) {
			json.addProperty("elapsedSeconds", elapsedSeconds);
		}
		if (reason != null) {
			json.addProperty("reason", reason);
		}
		return json;
	}

	/** "45s", "1m 12s". */
	static String duration(long seconds) {
		return seconds < 60 ? seconds + "s" : (seconds / 60) + "m " + (seconds % 60) + "s";
	}
}
