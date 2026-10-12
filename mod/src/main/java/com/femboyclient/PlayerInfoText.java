package com.femboyclient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The words the player screen shows for a {@link PlayerInfo}. Kept free of Minecraft classes so it can be tested on its own.
 */
public final class PlayerInfoText {

	/** The website shows times in Toronto time, so the screen does too. */
	private static final ZoneId ZONE = ZoneId.of("America/Toronto");
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH).withZone(ZONE);

	private PlayerInfoText() {
	}

	/** "Sep 21, 11:01 AM" for a UTC time like "2026-09-21T15:01:00.000Z". Anything unreadable is shown as it is. */
	public static String when(String isoUtc) {
		if (isoUtc == null) {
			return "unknown time";
		}
		try {
			return WHEN.format(Instant.parse(isoUtc));
		} catch (DateTimeParseException e) {
			return isoUtc;
		}
	}

	/** "Discord: name", "Discord: none linked", or "Discord: unavailable (why)". */
	public static String discordLine(PlayerInfo info) {
		if (info.discord() != null) {
			return "Discord: " + info.discord();
		}
		if (info.discordError() != null) {
			return "Discord: unavailable (" + info.discordError() + ")";
		}
		return "Discord: none linked";
	}

	/** Just the Discord name, "none linked", or "unavailable (why)": the screen shows a Discord icon in front of it. */
	public static String discordValue(PlayerInfo info) {
		if (info.discord() != null) {
			return info.discord();
		}
		return info.discordError() != null ? "unavailable (" + info.discordError() + ")" : "none linked";
	}

	/** The networth for its stat card: "1.7B", or why there is none ("API off", "No profile", "N/A"). */
	public static String networthValue(PlayerInfo info) {
		if (info.networth() != null) {
			return CompactNumber.format(info.networth());
		}
		if ("api_off".equals(info.networthNote())) {
			return "API off";
		}
		return "no_profile".equals(info.networthNote()) ? "No profile" : "N/A";
	}

	/** True if the name contains the typed search, ignoring capitals. An empty search matches everyone. */
	public static boolean matches(String name, String query) {
		return query == null || query.isBlank() || name.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
	}

	/** "Times met: 3". */
	public static String timesMetLine(PlayerInfo info) {
		return "Times met: " + info.timesMet();
	}

	/** "Chat logs (12)" or "Chat logs: none yet". */
	public static String chatTitle(PlayerInfo info) {
		return info.chatLogs().isEmpty() ? "Chat logs: none yet" : "Chat logs (" + info.chatLogs().size() + ")";
	}

	/** The line above a message: the time, then the channel and world when they say something ("Public" and "Unknown" do not). */
	public static String logHeader(PlayerInfo.ChatLog log) {
		StringBuilder line = new StringBuilder(when(log.at()));
		if (log.channel() != null && !log.channel().isBlank() && !log.channel().equals("Public")) {
			line.append(" · ").append(log.channel());
		}
		if (log.world() != null && !log.world().isBlank() && !log.world().equals("Unknown")) {
			line.append(" · ").append(log.world());
		}
		return line.toString();
	}
}
