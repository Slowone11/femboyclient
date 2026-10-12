package com.femboyclient;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * Watches the SkyBlock purse once per second and records every change.
 *
 * A change is logged, written to the CSV, and queued for upload together with the
 * chat message that most likely caused it.
 */
public final class PurseTracker {

	private static final Logger LOGGER = LoggerFactory.getLogger(FemboyClientMod.MOD_ID);

	/** The client ticks 20 times a second, so 20 ticks is one check per second. */
	private static final int TICKS_PER_CHECK = 20;

	/**
	 * How many consecutive one-second checks without a SkyBlock sidebar before we
	 * call the session over. Warping between islands briefly clears the sidebar,
	 * so a short grace period stops one session being split into many.
	 * Set this to 1 if you want the session to end the instant the sidebar goes.
	 */
	private static final int SESSION_END_GRACE_CHECKS = 30;

	private int tickCounter = 0;
	private int checksWithoutSkyBlock = 0;

	/** The session currently in progress, or null if we are not on SkyBlock. */
	private Session session = null;

	/** Last purse value we saw, or null if we have not read one yet this session. */
	private Long lastPurse = null;

	/** Called every client tick; does the real work once per second. */
	public void onClientTick(Minecraft mc) {
		tickCounter++;
		if (tickCounter < TICKS_PER_CHECK) {
			return;
		}
		tickCounter = 0;
		check(mc);
	}

	/** Called when the client disconnects from a server. */
	public void onDisconnect() {
		endSession("disconnected");
	}

	private void check(Minecraft mc) {
		if (mc.player == null) {
			endSession("no player");
			return;
		}

		ScoreboardReader.Sidebar sidebar = ScoreboardReader.read(mc);
		boolean onSkyBlock = sidebar != null && isSkyBlock(sidebar.title());

		if (!onSkyBlock) {
			// Not on SkyBlock right now. End the session only after the grace period.
			if (session != null) {
				checksWithoutSkyBlock++;
				if (checksWithoutSkyBlock >= SESSION_END_GRACE_CHECKS) {
					endSession("left SkyBlock");
				}
			}
			return;
		}

		checksWithoutSkyBlock = 0;

		if (session == null) {
			startSession(sidebar);
		}

		Long purse = PurseParser.findPurse(sidebar.lines());
		if (purse == null) {
			// No purse line this tick (loading screen, menu open, etc). Ignore it.
			return;
		}

		if (lastPurse == null) {
			lastPurse = purse;
			LOGGER.info("[{}] starting purse: {}", shortId(), purse);
			// Not a change, but the graph needs a point to start the session from.
			record(purse, 0, "session_start", "");
			return;
		}

		if (!purse.equals(lastPurse)) {
			long change = purse - lastPurse;
			lastPurse = purse;

			// Look back over the last few seconds of chat and blame the best match.
			CauseClassifier.Result cause =
					CauseClassifier.classify(FemboyClientMod.CHAT_CAPTURE.recent());

			LOGGER.info("[{}] purse {} ({}{}) cause={} \"{}\"",
					shortId(), purse, change > 0 ? "+" : "", change,
					cause.label(), cause.message());

			record(purse, change, cause.label(), cause.message());
		}
	}

	private void startSession(ScoreboardReader.Sidebar sidebar) {
		// The launcher account, not the in-world player, so it matches what the
		// website linked this PC to.
		AccountInfo who = Account.current();
		session = Session.start(who.uuid(), who.name());
		lastPurse = null;
		FemboyClientMod.SERVER.setStatus(true, session.id());

		LOGGER.info("session start {} for {} ({})", session.id(), session.playerName(), session.playerUuid());

		// The full sidebar is only useful when debugging why a purse is not found.
		LOGGER.debug("sidebar title: \"{}\"", sidebar.title());
		for (String line : sidebar.lines()) {
			LOGGER.debug("  sidebar line: \"{}\"", line);
		}
	}

	private void endSession(String reason) {
		if (session == null) {
			return;
		}
		LOGGER.info("session end {} ({})", session.id(), reason);
		FemboyClientMod.CHAT_CAPTURE.clear();
		FemboyClientMod.SERVER.setStatus(false, null);
		session = null;
		lastPurse = null;
		checksWithoutSkyBlock = 0;
	}

	/** Builds a row, appends it to the CSV and queues it for upload, if switched on. */
	private void record(long coins, long change, String causeLabel, String causeMessage) {
		if (session == null || !FemboyClientConfig.get().enabled) {
			return;
		}

		CoinRow row = new CoinRow(
				session.id(),
				session.playerUuid(),
				session.playerName(),
				OffsetDateTime.now(),
				coins,
				change,
				causeLabel,
				causeMessage);

		FemboyClientMod.CSV_LOGGER.append(row);
		FemboyClientMod.SERVER.enqueue(row);
	}

	/** First 8 characters of the session id, so log lines stay readable. */
	private String shortId() {
		return session == null ? "no-session" : session.id().substring(0, 8);
	}

	/** SkyBlock's sidebar heading contains "SKYBLOCK" (sometimes with extra decoration). */
	private static boolean isSkyBlock(String title) {
		return title.toUpperCase(Locale.ROOT).contains("SKYBLOCK");
	}
}
